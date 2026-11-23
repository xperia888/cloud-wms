package com.cloudwms.inventory;

import com.cloudwms.inventory.InventoryException.Code;
import com.cloudwms.inventory.model.InventoryModels.BalanceRow;
import com.cloudwms.inventory.model.InventoryModels.InventoryStatus;
import com.cloudwms.inventory.model.InventoryModels.OpContext;
import com.cloudwms.inventory.model.InventoryModels.OpResult;
import com.cloudwms.inventory.model.InventoryModels.ReconcileDiff;
import com.cloudwms.inventory.model.InventoryModels.StockKey;
import com.cloudwms.inventory.model.InventoryModels.TxnType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 库存引擎：所有库存变动的唯一入口。
 *
 * 每个公开方法 = 一个数据库事务 = 一个流水组。余额更新与流水写入同时提交或同时回滚，
 * 因此余额表永远等于流水累计（可用 {@link #reconcile()} 每日核对）。
 *
 * 入库、出库、盘点、调拨等业务模块只调用这里，不直接写 inventory_balance / inventory_txn。
 */
@Service
public class InventoryService {

    private final InventoryRepository repo;
    private final LpnService lpnService;
    private final NamedParameterJdbcTemplate jdbc;

    public InventoryService(InventoryRepository repo, LpnService lpnService, NamedParameterJdbcTemplate jdbc) {
        this.repo = repo;
        this.lpnService = lpnService;
        this.jdbc = jdbc;
    }

    // ================================================================== 增加库存

    /** 收货：把商品记到某库位（可指定 LPN）。 */
    @Transactional
    public OpResult receive(OpContext ctx, StockKey key, int qty) {
        return addStock(ctx, "RECEIVE", TxnType.RECEIVE, key, qty);
    }

    /** 退货入库 */
    @Transactional
    public OpResult receiveReturn(OpContext ctx, StockKey key, int qty) {
        return addStock(ctx, "RETURN", TxnType.RETURN_RECEIVE, key, qty);
    }

    /** 旧系统数据迁移：按库位 / 批次 / LPN 导入期初库存，流水类型单独标记为 MIGRATION */
    @Transactional
    public OpResult migrateOpening(OpContext ctx, StockKey key, int qty) {
        return addStock(ctx, "MIGRATION", TxnType.MIGRATION, key, qty);
    }

    private OpResult addStock(OpContext ctx, String groupType, TxnType txnType, StockKey key, int qty) {
        requirePositive(qty);
        validateKey(ctx.tenantId(), key);
        Optional<Long> group = repo.insertGroup(ctx, groupType);
        if (group.isEmpty()) {
            return duplicate(ctx);
        }
        repo.addOnHand(ctx.tenantId(), key, qty);
        repo.insertTxn(group.get(), ctx.tenantId(), txnType, key, qty);
        return new OpResult(group.get(), false);
    }

    // ================================================================== 移动

    /**
     * 散货 / 部分移库：从 from 扣减可用量，加到目标库位（可放入目标 LPN）。
     * 已分配给出库单的数量不能被移走。
     */
    @Transactional
    public OpResult move(OpContext ctx, StockKey from, long toLocationId, Long toLpnId, int qty) {
        requirePositive(qty);
        validateKey(ctx.tenantId(), from);
        StockKey to = from.withLocation(toLocationId).withLpn(toLpnId);
        validateKey(ctx.tenantId(), to);
        if (from.equals(to)) {
            throw InventoryException.invalid("source and target are the same");
        }
        Optional<Long> group = repo.insertGroup(ctx, "MOVE");
        if (group.isEmpty()) {
            return duplicate(ctx);
        }
        if (!repo.deductAvailable(from, qty)) {
            throw InventoryException.insufficientAvailable(from, qty);
        }
        repo.addOnHand(ctx.tenantId(), to, qty);
        repo.insertTxn(group.get(), ctx.tenantId(), TxnType.MOVE_OUT, from, -qty);
        repo.insertTxn(group.get(), ctx.tenantId(), TxnType.MOVE_IN, to, qty);
        return new OpResult(group.get(), false);
    }

    /**
     * 整托移动：扫一个 LPN，把该托盘（含其下所有箱 LPN）上的全部库存移到新库位。
     * 托盘上有已分配库存时拒绝移动，避免出库单找不到货。
     */
    @Transactional
    public OpResult moveLpn(OpContext ctx, long lpnId, long toLocationId) {
        List<Map<String, Object>> tree = lpnService.lockTree(lpnId);
        if (tree.isEmpty()) {
            throw InventoryException.notFound("LPN " + lpnId);
        }
        Map<String, Object> root = tree.get(0).get("id").equals(lpnId) ? tree.get(0)
                : tree.stream().filter(r -> ((Number) r.get("id")).longValue() == lpnId).findFirst().orElseThrow();
        if (((Number) root.get("tenant_id")).longValue() != ctx.tenantId()) {
            throw InventoryException.notFound("LPN " + lpnId);
        }
        if (!"ACTIVE".equals(root.get("status"))) {
            throw new InventoryException(Code.LPN_CONFLICT, "LPN " + lpnId + " is " + root.get("status"));
        }
        long warehouseId = ((Number) root.get("warehouse_id")).longValue();
        requireActiveLocation(warehouseId, toLocationId);

        Optional<Long> group = repo.insertGroup(ctx, "LPN_MOVE");
        if (group.isEmpty()) {
            return duplicate(ctx);
        }
        List<Long> ids = tree.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
        List<BalanceRow> rows = repo.lockBalancesOfLpns(ids);
        for (BalanceRow b : rows) {
            if (b.qtyAllocated() > 0) {
                throw new InventoryException(Code.LPN_CONFLICT,
                        "LPN " + lpnId + " has allocated stock; release or pick it before moving the pallet");
            }
        }
        for (BalanceRow b : rows) {
            if (b.locationId() == toLocationId) {
                continue;
            }
            StockKey oldKey = toKey(b);
            repo.moveBalanceLocation(b.id(), toLocationId);
            repo.insertTxn(group.get(), ctx.tenantId(), TxnType.MOVE_OUT, oldKey, -b.qtyOnHand());
            repo.insertTxn(group.get(), ctx.tenantId(), TxnType.MOVE_IN, oldKey.withLocation(toLocationId),
                    b.qtyOnHand());
        }
        lpnService.setLocation(ids, toLocationId);
        return new OpResult(group.get(), false);
    }

    // ================================================================== 调整 / 状态

    /**
     * 库存调整（盘盈为正、盘亏为负）。countAdjust = true 表示来自盘点。
     * 盘亏只能扣可用部分，已分配数量需先释放。
     */
    @Transactional
    public OpResult adjust(OpContext ctx, StockKey key, int delta, boolean countAdjust) {
        if (delta == 0) {
            throw InventoryException.invalid("delta must not be 0");
        }
        if (ctx.reason() == null || ctx.reason().isBlank()) {
            throw InventoryException.invalid("reason is required for adjustments");
        }
        validateKey(ctx.tenantId(), key);
        Optional<Long> group = repo.insertGroup(ctx, countAdjust ? "COUNT" : "ADJUST");
        if (group.isEmpty()) {
            return duplicate(ctx);
        }
        if (delta > 0) {
            repo.addOnHand(ctx.tenantId(), key, delta);
        } else if (!repo.deductAvailable(key, -delta)) {
            throw InventoryException.insufficientAvailable(key, -delta);
        }
        repo.insertTxn(group.get(), ctx.tenantId(), countAdjust ? TxnType.COUNT_ADJUST : TxnType.ADJUST, key, delta);
        return new OpResult(group.get(), false);
    }

    /** 库存状态变更：如可用 → 冻结、待检 → 可用、可用 → 残次 */
    @Transactional
    public OpResult changeStatus(OpContext ctx, StockKey key, InventoryStatus newStatus, int qty) {
        requirePositive(qty);
        if (key.status() == newStatus) {
            throw InventoryException.invalid("status is already " + newStatus);
        }
        validateKey(ctx.tenantId(), key);
        Optional<Long> group = repo.insertGroup(ctx, "STATUS_CHANGE");
        if (group.isEmpty()) {
            return duplicate(ctx);
        }
        if (!repo.deductAvailable(key, qty)) {
            throw InventoryException.insufficientAvailable(key, qty);
        }
        StockKey target = key.withStatus(newStatus);
        repo.addOnHand(ctx.tenantId(), target, qty);
        repo.insertTxn(group.get(), ctx.tenantId(), TxnType.STATUS_OUT, key, -qty);
        repo.insertTxn(group.get(), ctx.tenantId(), TxnType.STATUS_IN, target, qty);
        return new OpResult(group.get(), false);
    }

    // ================================================================== 分配 / 出库（供出库模块调用）

    /** 为出库单锁定库存。不产生流水（在库量未变），分配明细由出库模块的 allocation 表记录。 */
    @Transactional
    public void allocate(StockKey key, int qty) {
        requirePositive(qty);
        if (!repo.allocate(key, qty)) {
            throw InventoryException.insufficientAvailable(key, qty);
        }
    }

    @Transactional
    public void deallocate(StockKey key, int qty) {
        requirePositive(qty);
        if (!repo.deallocate(key, qty)) {
            throw new InventoryException(Code.INSUFFICIENT_ALLOCATED,
                    "Allocated quantity is less than " + qty + " for " + key);
        }
    }

    /** 发货：按已分配数量扣减在库量 */
    @Transactional
    public OpResult shipAllocated(OpContext ctx, StockKey key, int qty) {
        requirePositive(qty);
        Optional<Long> group = repo.insertGroup(ctx, "SHIP");
        if (group.isEmpty()) {
            return duplicate(ctx);
        }
        if (!repo.deductAllocated(key, qty)) {
            throw new InventoryException(Code.INSUFFICIENT_ALLOCATED,
                    "Allocated quantity is less than " + qty + " for " + key);
        }
        repo.insertTxn(group.get(), ctx.tenantId(), TxnType.SHIP, key, -qty);
        return new OpResult(group.get(), false);
    }

    // ================================================================== 查询 / 对账

    @Transactional(readOnly = true)
    public List<BalanceRow> query(long tenantId, long warehouseId, Long customerId, Long skuId,
                                  Long locationId, Long lpnId, int limit) {
        return repo.queryBalances(tenantId, warehouseId, customerId, skuId, locationId, lpnId,
                Math.min(Math.max(limit, 1), 1000));
    }

    /** 余额与流水累计不一致的行；正常情况下应为空。建议每日定时执行并告警。 */
    @Transactional(readOnly = true)
    public List<ReconcileDiff> reconcile() {
        return repo.reconcile();
    }

    // ================================================================== 校验

    private OpResult duplicate(OpContext ctx) {
        long existing = repo.findGroupByKey(ctx.tenantId(), ctx.idempotencyKey()).orElseThrow();
        return new OpResult(existing, true);
    }

    private static void requirePositive(int qty) {
        if (qty <= 0) {
            throw InventoryException.invalid("qty must be > 0");
        }
    }

    private static StockKey toKey(BalanceRow b) {
        return new StockKey(b.warehouseId(), b.locationId(), b.lpnId(), b.customerId(), b.skuId(),
                b.lotId(), b.status());
    }

    private static final String SQL_VALIDATE_KEY = """
            SELECT l.warehouse_id  AS loc_wh, l.status AS loc_status,
                   w.tenant_id     AS wh_tenant,
                   s.customer_id   AS sku_customer, s.tenant_id AS sku_tenant, s.lot_controlled,
                   t.sku_id        AS lot_sku,
                   p.warehouse_id  AS lpn_wh, p.location_id AS lpn_loc, p.status AS lpn_status,
                   p.tenant_id     AS lpn_tenant
              FROM location l
              JOIN warehouse w ON w.id = l.warehouse_id
              JOIN sku s ON s.id = :skuId
              LEFT JOIN lot t ON t.id = CAST(:lotId AS BIGINT)
              LEFT JOIN lpn p ON p.id = CAST(:lpnId AS BIGINT)
             WHERE l.id = :locationId""";

    /** 校验键的各部分属于同一租户 / 仓库，且业务规则成立 */
    private void validateKey(long tenantId, StockKey key) {
        var rows = jdbc.queryForList(SQL_VALIDATE_KEY, InventoryRepository.keyParams(key));
        if (rows.isEmpty()) {
            throw InventoryException.notFound("location " + key.locationId() + " or sku " + key.skuId());
        }
        Map<String, Object> r = rows.get(0);
        if (num(r, "wh_tenant") != tenantId || num(r, "sku_tenant") != tenantId) {
            throw InventoryException.notFound("location or sku");
        }
        if (num(r, "loc_wh") != key.warehouseId()) {
            throw InventoryException.invalid("location " + key.locationId() + " is not in warehouse "
                    + key.warehouseId());
        }
        if (!"ACTIVE".equals(r.get("loc_status"))) {
            throw new InventoryException(Code.LOCATION_BLOCKED, "location " + key.locationId() + " is "
                    + r.get("loc_status"));
        }
        if (num(r, "sku_customer") != key.customerId()) {
            throw InventoryException.invalid("sku " + key.skuId() + " does not belong to customer "
                    + key.customerId());
        }
        if (Boolean.TRUE.equals(r.get("lot_controlled")) && key.lotId() == null) {
            throw InventoryException.invalid("sku " + key.skuId() + " is lot-controlled; lotId is required");
        }
        if (key.lotId() != null && (r.get("lot_sku") == null || num(r, "lot_sku") != key.skuId())) {
            throw InventoryException.invalid("lot " + key.lotId() + " does not belong to sku " + key.skuId());
        }
        if (key.lpnId() != null) {
            if (r.get("lpn_wh") == null || num(r, "lpn_tenant") != tenantId) {
                throw InventoryException.notFound("LPN " + key.lpnId());
            }
            if (num(r, "lpn_wh") != key.warehouseId()) {
                throw InventoryException.invalid("LPN " + key.lpnId() + " is in another warehouse");
            }
            if (!"ACTIVE".equals(r.get("lpn_status"))) {
                throw new InventoryException(Code.LPN_CONFLICT, "LPN " + key.lpnId() + " is " + r.get("lpn_status"));
            }
            Object lpnLoc = r.get("lpn_loc");
            if (lpnLoc == null) {
                // 新 LPN 首次放货：绑定到该库位
                lpnService.setLocation(List.of(key.lpnId()), key.locationId());
            } else if (((Number) lpnLoc).longValue() != key.locationId()) {
                throw new InventoryException(Code.LPN_CONFLICT, "LPN " + key.lpnId() + " is at location "
                        + lpnLoc + ", not " + key.locationId() + "; move the whole LPN instead");
            }
        }
    }

    private void requireActiveLocation(long warehouseId, long locationId) {
        var rows = jdbc.queryForList("SELECT warehouse_id, status FROM location WHERE id = :id",
                new MapSqlParameterSource("id", locationId));
        if (rows.isEmpty()) {
            throw InventoryException.notFound("location " + locationId);
        }
        if (num(rows.get(0), "warehouse_id") != warehouseId) {
            throw InventoryException.invalid("location " + locationId + " is not in warehouse " + warehouseId);
        }
        if (!"ACTIVE".equals(rows.get(0).get("status"))) {
            throw new InventoryException(Code.LOCATION_BLOCKED, "location " + locationId + " is "
                    + rows.get(0).get("status"));
        }
    }

    private static long num(Map<String, Object> row, String col) {
        return ((Number) row.get(col)).longValue();
    }
}
