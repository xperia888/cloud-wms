package com.cloudwms.inventory;

import com.cloudwms.inventory.model.InventoryModels.BalanceRow;
import com.cloudwms.inventory.model.InventoryModels.InventoryStatus;
import com.cloudwms.inventory.model.InventoryModels.OpContext;
import com.cloudwms.inventory.model.InventoryModels.ReconcileDiff;
import com.cloudwms.inventory.model.InventoryModels.StockKey;
import com.cloudwms.inventory.model.InventoryModels.TxnType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * 库存余额与流水的 SQL。
 *
 * 并发规则（PostgreSQL 默认 READ COMMITTED 即可保证正确）：
 *  - 增加库存：INSERT ... ON CONFLICT DO UPDATE，原子累加。
 *  - 扣减库存：带条件的 UPDATE（可用量 >= 扣减量才更新）。两个事务同时扣同一行时，
 *    后到者会等待前者提交，再用最新值重新判断 WHERE 条件，因此不会扣成负数，也不会超卖。
 *  - 数据库 CHECK 约束（qty_on_hand >= 0、qty_allocated <= qty_on_hand）作为最后一道保险。
 *
 * SQL 写成常量，便于评审，也便于在数据库上单独验证。
 */
@Repository
public class InventoryRepository {

    // 键匹配条件：lpn_id / lot_id 可能为 null，用 IS NOT DISTINCT FROM
    private static final String KEY_MATCH = """
            location_id = :locationId
              AND lpn_id IS NOT DISTINCT FROM CAST(:lpnId AS BIGINT)
              AND customer_id = :customerId
              AND sku_id = :skuId
              AND lot_id IS NOT DISTINCT FROM CAST(:lotId AS BIGINT)
              AND inv_status = :status""";

    public static final String SQL_INSERT_GROUP = """
            INSERT INTO inventory_txn_group
                   (tenant_id, group_type, idempotency_key, ref_type, ref_id, reason, created_by)
            VALUES (:tenantId, :groupType, :idempotencyKey, :refType, :refId, :reason, :operator)
            ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
            RETURNING id""";

    public static final String SQL_FIND_GROUP_BY_KEY = """
            SELECT id FROM inventory_txn_group
            WHERE tenant_id = :tenantId AND idempotency_key = :idempotencyKey""";

    public static final String SQL_INSERT_TXN = """
            INSERT INTO inventory_txn
                   (group_id, tenant_id, warehouse_id, txn_type, location_id, lpn_id,
                    customer_id, sku_id, lot_id, inv_status, qty_delta)
            VALUES (:groupId, :tenantId, :warehouseId, :txnType, :locationId, :lpnId,
                    :customerId, :skuId, :lotId, :status, :qtyDelta)""";

    /** 增加在库量（不存在则新建余额行） */
    public static final String SQL_ADD_ON_HAND = """
            INSERT INTO inventory_balance
                   (tenant_id, warehouse_id, location_id, lpn_id, customer_id, sku_id, lot_id,
                    inv_status, qty_on_hand)
            VALUES (:tenantId, :warehouseId, :locationId, :lpnId, :customerId, :skuId, :lotId,
                    :status, :qty)
            ON CONFLICT ON CONSTRAINT uq_balance_key DO UPDATE
               SET qty_on_hand = inventory_balance.qty_on_hand + EXCLUDED.qty_on_hand,
                   version     = inventory_balance.version + 1,
                   updated_at  = now()
            RETURNING id""";

    /** 扣减可用量（未分配部分）。返回空 = 可用量不足 */
    public static final String SQL_DEDUCT_AVAILABLE = """
            UPDATE inventory_balance
               SET qty_on_hand = qty_on_hand - :qty,
                   version     = version + 1,
                   updated_at  = now()
             WHERE\s""" + KEY_MATCH + """

               AND qty_on_hand - qty_allocated >= :qty
            RETURNING id, qty_on_hand, qty_allocated""";

    /** 按已分配量出库：在库量与分配量同时扣减。返回空 = 已分配量不足 */
    public static final String SQL_DEDUCT_ALLOCATED = """
            UPDATE inventory_balance
               SET qty_on_hand   = qty_on_hand - :qty,
                   qty_allocated = qty_allocated - :qty,
                   version       = version + 1,
                   updated_at    = now()
             WHERE\s""" + KEY_MATCH + """

               AND qty_allocated >= :qty
            RETURNING id, qty_on_hand, qty_allocated""";

    /** 分配（锁定）可用库存给出库单 */
    public static final String SQL_ALLOCATE = """
            UPDATE inventory_balance
               SET qty_allocated = qty_allocated + :qty,
                   version       = version + 1,
                   updated_at    = now()
             WHERE\s""" + KEY_MATCH + """

               AND qty_on_hand - qty_allocated >= :qty
            RETURNING id""";

    /** 释放分配 */
    public static final String SQL_DEALLOCATE = """
            UPDATE inventory_balance
               SET qty_allocated = qty_allocated - :qty,
                   version       = version + 1,
                   updated_at    = now()
             WHERE\s""" + KEY_MATCH + """

               AND qty_allocated >= :qty
            RETURNING id""";

    /** 清理数量归零的余额行，保持余额表精简（流水仍完整保留） */
    public static final String SQL_DELETE_EMPTY = """
            DELETE FROM inventory_balance
             WHERE id = :id AND qty_on_hand = 0 AND qty_allocated = 0""";

    /** 锁定某个 LPN（含子 LPN）下的全部余额行，用于整托移动 */
    public static final String SQL_LOCK_LPN_BALANCES = """
            SELECT b.*, NULL::text AS location_code, NULL::text AS lpn_no,
                   NULL::text AS sku_code, NULL::text AS lot_no
              FROM inventory_balance b
             WHERE b.lpn_id IN (:lpnIds)
             ORDER BY b.id
               FOR UPDATE""";

    /** 整托移动：直接改余额行的库位（同一 LPN 的所有行都在同一库位，不会撞唯一键） */
    public static final String SQL_MOVE_BALANCE_LOCATION = """
            UPDATE inventory_balance
               SET location_id = :toLocationId,
                   version     = version + 1,
                   updated_at  = now()
             WHERE id = :id""";

    public static final String SQL_QUERY_BALANCES = """
            SELECT b.*, l.code AS location_code, p.lpn_no, s.sku_code, t.lot_no
              FROM inventory_balance b
              JOIN location l ON l.id = b.location_id
              JOIN sku s      ON s.id = b.sku_id
              LEFT JOIN lpn p ON p.id = b.lpn_id
              LEFT JOIN lot t ON t.id = b.lot_id
             WHERE b.tenant_id = :tenantId
               AND b.warehouse_id = :warehouseId
               AND (CAST(:customerId AS BIGINT) IS NULL OR b.customer_id = :customerId)
               AND (CAST(:skuId AS BIGINT)      IS NULL OR b.sku_id = :skuId)
               AND (CAST(:locationId AS BIGINT) IS NULL OR b.location_id = :locationId)
               AND (CAST(:lpnId AS BIGINT)      IS NULL OR b.lpn_id = :lpnId)
             ORDER BY l.code, p.lpn_no NULLS LAST, s.sku_code
             LIMIT :limit""";

    public static final String SQL_RECONCILE = "SELECT * FROM v_inventory_reconcile";

    private final NamedParameterJdbcTemplate jdbc;

    public InventoryRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ 流水组 / 流水

    /**
     * 新建流水组。若幂等键已存在则返回 empty（调用方应视为重复请求，直接返回原结果）。
     * 同一幂等键并发提交时，后到者会等待先到者提交后再走 DO NOTHING。
     */
    public Optional<Long> insertGroup(OpContext ctx, String groupType) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", ctx.tenantId())
                .addValue("groupType", groupType)
                .addValue("idempotencyKey", ctx.idempotencyKey())
                .addValue("refType", ctx.refType())
                .addValue("refId", ctx.refId())
                .addValue("reason", ctx.reason())
                .addValue("operator", ctx.operator());
        List<Long> ids = jdbc.queryForList(SQL_INSERT_GROUP, params, Long.class);
        return ids.stream().findFirst();
    }

    public Optional<Long> findGroupByKey(long tenantId, String idempotencyKey) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("idempotencyKey", idempotencyKey);
        return jdbc.queryForList(SQL_FIND_GROUP_BY_KEY, params, Long.class).stream().findFirst();
    }

    public void insertTxn(long groupId, long tenantId, TxnType type, StockKey key, int qtyDelta) {
        var params = keyParams(key)
                .addValue("groupId", groupId)
                .addValue("tenantId", tenantId)
                .addValue("txnType", type.name())
                .addValue("qtyDelta", qtyDelta);
        jdbc.update(SQL_INSERT_TXN, params);
    }

    // ------------------------------------------------------------------ 余额

    public long addOnHand(long tenantId, StockKey key, int qty) {
        var params = keyParams(key).addValue("tenantId", tenantId).addValue("qty", qty);
        return jdbc.queryForObject(SQL_ADD_ON_HAND, params, Long.class);
    }

    /** @return true = 扣减成功；false = 可用量不足 */
    public boolean deductAvailable(StockKey key, int qty) {
        return deductAndCleanup(SQL_DEDUCT_AVAILABLE, key, qty);
    }

    /** @return true = 扣减成功；false = 已分配量不足 */
    public boolean deductAllocated(StockKey key, int qty) {
        return deductAndCleanup(SQL_DEDUCT_ALLOCATED, key, qty);
    }

    public boolean allocate(StockKey key, int qty) {
        return !jdbc.queryForList(SQL_ALLOCATE, keyParams(key).addValue("qty", qty), Long.class).isEmpty();
    }

    public boolean deallocate(StockKey key, int qty) {
        return !jdbc.queryForList(SQL_DEALLOCATE, keyParams(key).addValue("qty", qty), Long.class).isEmpty();
    }

    public List<BalanceRow> lockBalancesOfLpns(List<Long> lpnIds) {
        return jdbc.query(SQL_LOCK_LPN_BALANCES, new MapSqlParameterSource("lpnIds", lpnIds), BALANCE_MAPPER);
    }

    public void moveBalanceLocation(long balanceId, long toLocationId) {
        jdbc.update(SQL_MOVE_BALANCE_LOCATION, new MapSqlParameterSource()
                .addValue("id", balanceId)
                .addValue("toLocationId", toLocationId));
    }

    public List<BalanceRow> queryBalances(long tenantId, long warehouseId, Long customerId, Long skuId,
                                          Long locationId, Long lpnId, int limit) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("warehouseId", warehouseId)
                .addValue("customerId", customerId)
                .addValue("skuId", skuId)
                .addValue("locationId", locationId)
                .addValue("lpnId", lpnId)
                .addValue("limit", limit);
        return jdbc.query(SQL_QUERY_BALANCES, params, BALANCE_MAPPER);
    }

    public List<ReconcileDiff> reconcile() {
        return jdbc.query(SQL_RECONCILE, (rs, i) -> new ReconcileDiff(
                rs.getLong("location_id"),
                nullableLong(rs, "lpn_id"),
                rs.getLong("customer_id"),
                rs.getLong("sku_id"),
                nullableLong(rs, "lot_id"),
                InventoryStatus.valueOf(rs.getString("inv_status")),
                rs.getInt("qty_balance"),
                rs.getInt("qty_from_txn")));
    }

    // ------------------------------------------------------------------ helpers

    private boolean deductAndCleanup(String sql, StockKey key, int qty) {
        var rows = jdbc.queryForList(sql, keyParams(key).addValue("qty", qty));
        if (rows.isEmpty()) {
            return false;
        }
        var row = rows.get(0);
        if (((Number) row.get("qty_on_hand")).intValue() == 0
                && ((Number) row.get("qty_allocated")).intValue() == 0) {
            jdbc.update(SQL_DELETE_EMPTY, new MapSqlParameterSource("id", row.get("id")));
        }
        return true;
    }

    static MapSqlParameterSource keyParams(StockKey key) {
        return new MapSqlParameterSource()
                .addValue("warehouseId", key.warehouseId())
                .addValue("locationId", key.locationId())
                .addValue("lpnId", key.lpnId())
                .addValue("customerId", key.customerId())
                .addValue("skuId", key.skuId())
                .addValue("lotId", key.lotId())
                .addValue("status", key.status().name());
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    static final RowMapper<BalanceRow> BALANCE_MAPPER = (rs, i) -> new BalanceRow(
            rs.getLong("id"),
            rs.getLong("warehouse_id"),
            rs.getLong("location_id"),
            rs.getString("location_code"),
            nullableLong(rs, "lpn_id"),
            rs.getString("lpn_no"),
            rs.getLong("customer_id"),
            rs.getLong("sku_id"),
            rs.getString("sku_code"),
            nullableLong(rs, "lot_id"),
            rs.getString("lot_no"),
            InventoryStatus.valueOf(rs.getString("inv_status")),
            rs.getInt("qty_on_hand"),
            rs.getInt("qty_allocated"));
}
