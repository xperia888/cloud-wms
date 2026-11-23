package com.cloudwms.outbound;

import com.cloudwms.carrier.CarrierAdapter;
import com.cloudwms.carrier.CarrierModels.Address;
import com.cloudwms.carrier.CarrierModels.CarrierAccount;
import com.cloudwms.carrier.CarrierModels.CarrierException;
import com.cloudwms.carrier.CarrierModels.LabelRequest;
import com.cloudwms.carrier.CarrierModels.LabelResult;
import com.cloudwms.carrier.CarrierModels.Parcel;
import com.cloudwms.carrier.CarrierModels.RateQuote;
import com.cloudwms.carrier.CarrierRegistry;
import com.cloudwms.carrier.RateShopper;
import com.cloudwms.inventory.InventoryService;
import com.cloudwms.inventory.model.InventoryModels.InventoryStatus;
import com.cloudwms.inventory.model.InventoryModels.OpContext;
import com.cloudwms.inventory.model.InventoryModels.StockKey;
import com.cloudwms.outbound.OutboundModels.CreateOrderRequest;
import com.cloudwms.outbound.OutboundModels.LineRequest;
import com.cloudwms.outbound.OutboundModels.LineView;
import com.cloudwms.outbound.OutboundModels.OrderStatus;
import com.cloudwms.outbound.OutboundModels.OrderView;
import com.cloudwms.outbound.OutboundModels.OutboundException;
import com.cloudwms.outbound.OutboundModels.OutboundException.Code;
import com.cloudwms.outbound.OutboundModels.PackItem;
import com.cloudwms.outbound.OutboundModels.PackRequest;
import com.cloudwms.outbound.OutboundModels.PackageRequest;
import com.cloudwms.outbound.OutboundModels.PackageView;
import com.cloudwms.outbound.OutboundModels.RateShopView;
import com.cloudwms.outbound.OutboundModels.ShipRequest;
import com.cloudwms.outbound.OutboundModels.ShipView;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Array;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 小包出库流程：建单 → 分配 → 拣货 → 打包称重 → 比价 → 打单发货。
 *
 * 状态：NEW → ALLOCATED → PICKED → PACKED → SHIPPED；NEW～PACKED 可取消（释放分配）。
 *
 * 一期简化：拣货按“整单确认”，库存在发货时从原库位按分配扣减（不经中转库位）。
 * 后续在 PDA 上改为逐行扫码拣货，并把货移到打包台库位。
 */
@Service
public class OutboundService {

    private static final Logger log = LoggerFactory.getLogger(OutboundService.class);

    // ================================================================== SQL

    public static final String SQL_CHECK_OWNERSHIP = """
            SELECT (SELECT tenant_id FROM warehouse WHERE id = :warehouseId) AS wh_tenant,
                   (SELECT tenant_id FROM customer  WHERE id = :customerId AND active) AS cust_tenant""";

    public static final String SQL_RESOLVE_SKU = """
            SELECT id FROM sku
             WHERE customer_id = :customerId AND active
               AND (id = CAST(:skuId AS BIGINT) OR sku_code = CAST(:skuCode AS VARCHAR))""";

    public static final String SQL_INSERT_ORDER = """
            INSERT INTO sales_order (tenant_id, warehouse_id, customer_id, order_no, source, external_order_no,
                                     ship_to, requested_service, reference)
            VALUES (:tenantId, :warehouseId, :customerId, 'SO' || nextval('order_no_seq'), :source,
                    :externalOrderNo, CAST(:shipTo AS JSONB), :requestedService, :reference)
            ON CONFLICT (customer_id, source, external_order_no) DO NOTHING
            RETURNING id""";

    public static final String SQL_FIND_ORDER_BY_EXTERNAL = """
            SELECT id FROM sales_order
             WHERE customer_id = :customerId AND source = :source AND external_order_no = :externalOrderNo""";

    public static final String SQL_INSERT_LINE = """
            INSERT INTO order_line (order_id, line_no, sku_id, qty_ordered)
            VALUES (:orderId, :lineNo, :skuId, :qty)""";

    public static final String SQL_LOCK_ORDER = """
            SELECT id, order_no, warehouse_id, customer_id, status, requested_service, ship_to::text AS ship_to
              FROM sales_order
             WHERE id = :orderId AND tenant_id = :tenantId
               FOR UPDATE""";

    public static final String SQL_SET_ORDER_STATUS = """
            UPDATE sales_order SET status = :status, updated_at = now() WHERE id = :orderId""";

    public static final String SQL_ORDER_LINES = """
            SELECT id, sku_id, qty_ordered, qty_allocated, qty_picked
              FROM order_line WHERE order_id = :orderId ORDER BY line_no""";

    /**
     * 分配候选：可拣库位上的可用库存，先到期先出（FEFO），再优先清空零散库位。
     * FOR UPDATE 锁住候选行：并发订单争抢同一 SKU 时排队执行，读到的可用量总是最新的，
     * 不会出现“拿着过期数量去扣、失败后跳过本可用的库位”。
     */
    public static final String SQL_ALLOCATION_CANDIDATES = """
            SELECT b.location_id, b.lpn_id, b.lot_id, b.qty_on_hand - b.qty_allocated AS available
              FROM inventory_balance b
              JOIN location l ON l.id = b.location_id
              LEFT JOIN lot t ON t.id = b.lot_id
             WHERE b.warehouse_id = :warehouseId
               AND b.customer_id  = :customerId
               AND b.sku_id       = :skuId
               AND b.inv_status   = 'AVAILABLE'
               AND l.status = 'ACTIVE' AND l.pickable
               AND b.qty_on_hand - b.qty_allocated > 0
             ORDER BY t.expiry_date NULLS LAST, b.qty_on_hand - b.qty_allocated, b.id
               FOR UPDATE OF b""";

    public static final String SQL_INSERT_ALLOCATION = """
            INSERT INTO allocation (order_line_id, warehouse_id, location_id, lpn_id, customer_id, sku_id, lot_id,
                                    inv_status, qty)
            VALUES (:lineId, :warehouseId, :locationId, :lpnId, :customerId, :skuId, :lotId, 'AVAILABLE', :qty)""";

    public static final String SQL_ADD_LINE_ALLOCATED = """
            UPDATE order_line SET qty_allocated = qty_allocated + :qty WHERE id = :lineId""";

    public static final String SQL_PICK_ALL_ALLOCATIONS = """
            UPDATE allocation a SET qty_picked = a.qty, status = 'PICKED'
              FROM order_line ol
             WHERE a.order_line_id = ol.id AND ol.order_id = :orderId AND a.status = 'OPEN'""";

    public static final String SQL_PICK_ALL_LINES = """
            UPDATE order_line SET qty_picked = qty_allocated WHERE order_id = :orderId""";

    public static final String SQL_BOX_TYPE = """
            SELECT id, length_in, width_in, height_in FROM box_type
             WHERE tenant_id = :tenantId AND code = :code AND active""";

    public static final String SQL_INSERT_PACKAGE = """
            INSERT INTO package (order_id, package_no, box_type_id, length_in, width_in, height_in, weight_lb)
            VALUES (:orderId, :packageNo, :boxTypeId, :lengthIn, :widthIn, :heightIn, :weightLb)
            RETURNING id""";

    public static final String SQL_INSERT_PACKAGE_ITEM = """
            INSERT INTO package_item (package_id, sku_id, qty) VALUES (:packageId, :skuId, :qty)""";

    public static final String SQL_PACKAGES = """
            SELECT id, package_no, length_in, width_in, height_in, weight_lb, tracking_no, cost
              FROM package WHERE order_id = :orderId ORDER BY package_no""";

    /** 每家承运商取一个账号：货主自带账号（BYOA）优先，否则用仓库账号 */
    public static final String SQL_CARRIER_ACCOUNTS = """
            SELECT DISTINCT ON (a.carrier_code)
                   a.id, a.carrier_code, a.account_no, a.credentials_ref, a.sandbox
              FROM carrier_account a
              JOIN carrier c ON c.code = a.carrier_code
             WHERE a.tenant_id = :tenantId AND a.active AND c.active AND c.mode = 'PARCEL'
               AND (a.customer_id = :customerId OR a.customer_id IS NULL)
             ORDER BY a.carrier_code, a.customer_id NULLS LAST, a.id""";

    public static final String SQL_SHIP_RULE = """
            SELECT strategy, allowed_services, max_transit_days FROM ship_rule WHERE customer_id = :customerId""";

    public static final String SQL_ACTIVE_SERVICES = """
            SELECT s.carrier_code || ':' || s.service_code AS svc
              FROM carrier_service s JOIN carrier c ON c.code = s.carrier_code
             WHERE s.active AND c.active""";

    public static final String SQL_WAREHOUSE_ADDRESS = """
            SELECT name, address::text AS address FROM warehouse WHERE id = :warehouseId""";

    public static final String SQL_INSERT_QUOTE = """
            INSERT INTO rate_quote (order_id, carrier_code, service_code, amount, currency, transit_days, selected)
            VALUES (:orderId, :carrierCode, :serviceCode, :amount, :currency, :transitDays, :selected)""";

    public static final String SQL_ACTIVE_SHIPMENTS = """
            SELECT count(*) FROM shipment WHERE order_id = :orderId AND status IN ('PENDING','LABELED')""";

    public static final String SQL_INSERT_SHIPMENT = """
            INSERT INTO shipment (order_id, carrier_code, service_code, carrier_account_id)
            VALUES (:orderId, :carrierCode, :serviceCode, :accountId)
            RETURNING id""";

    public static final String SQL_SHIPMENT_FAILED = """
            UPDATE shipment SET status = 'FAILED', error = :error WHERE id = :shipmentId""";

    public static final String SQL_SHIPMENT_VOIDED = """
            UPDATE shipment SET status = 'VOIDED', error = :error WHERE id = :shipmentId""";

    public static final String SQL_SHIPMENT_LABELED = """
            UPDATE shipment SET status = 'LABELED', total_cost = :totalCost, currency = :currency, labeled_at = now()
             WHERE id = :shipmentId""";

    public static final String SQL_PACKAGE_LABEL = """
            UPDATE package SET shipment_id = :shipmentId, tracking_no = :trackingNo, label_format = :labelFormat,
                               label_data = :labelData, cost = :cost
             WHERE id = :packageId""";

    public static final String SQL_PICKED_ALLOCATIONS = """
            SELECT a.id, a.warehouse_id, a.location_id, a.lpn_id, a.customer_id, a.sku_id, a.lot_id, a.qty,
                   a.qty_picked, a.status
              FROM allocation a JOIN order_line ol ON ol.id = a.order_line_id
             WHERE ol.order_id = :orderId AND a.status IN (:statuses)
             ORDER BY a.id""";

    public static final String SQL_SET_ALLOCATION_STATUS = """
            UPDATE allocation SET status = :status WHERE id = :allocationId""";

    public static final String SQL_LINES_SHIPPED = """
            UPDATE order_line SET qty_shipped = qty_picked WHERE order_id = :orderId""";

    public static final String SQL_LINES_RELEASED = """
            UPDATE order_line SET qty_picked = 0, qty_allocated = 0 WHERE order_id = :orderId""";

    public static final String SQL_ORDER_SHIPPED = """
            UPDATE sales_order SET status = 'SHIPPED', shipped_at = now(), updated_at = now() WHERE id = :orderId""";

    /** 计费事件：同一业务事件只记一次（唯一键 event_type + ref_type + ref_id） */
    public static final String SQL_INSERT_BILLABLE = """
            INSERT INTO billable_event (tenant_id, warehouse_id, customer_id, event_type, ref_type, ref_id,
                                        quantity, cost_amount)
            VALUES (:tenantId, :warehouseId, :customerId, :eventType, :refType, :refId, :quantity, :costAmount)
            ON CONFLICT (event_type, ref_type, ref_id) DO NOTHING""";

    public static final String SQL_ORDER_VIEW = """
            SELECT id, order_no, warehouse_id, customer_id, source, external_order_no, status, ship_to::text AS ship_to,
                   requested_service, created_at, shipped_at
              FROM sales_order WHERE id = :orderId AND tenant_id = :tenantId""";

    public static final String SQL_LINE_VIEW = """
            SELECT ol.id, ol.line_no, ol.sku_id, s.sku_code, ol.qty_ordered, ol.qty_allocated, ol.qty_picked,
                   ol.qty_shipped
              FROM order_line ol JOIN sku s ON s.id = ol.sku_id
             WHERE ol.order_id = :orderId ORDER BY ol.line_no""";

    public static final String SQL_PACKAGE_ITEMS = """
            SELECT pi.package_id, pi.sku_id, pi.qty
              FROM package_item pi JOIN package p ON p.id = pi.package_id
             WHERE p.order_id = :orderId""";

    public static final String SQL_LABEL = """
            SELECT p.label_format, p.label_data
              FROM package p JOIN sales_order o ON o.id = p.order_id
             WHERE p.id = :packageId AND o.tenant_id = :tenantId""";

    // ================================================================== 依赖

    private final NamedParameterJdbcTemplate jdbc;
    private final InventoryService inventory;
    private final CarrierRegistry carriers;
    private final RateShopper rateShopper;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    public OutboundService(NamedParameterJdbcTemplate jdbc, InventoryService inventory, CarrierRegistry carriers,
                           PlatformTransactionManager txManager, ObjectMapper objectMapper,
                           @Value("${wms.carriers.rate-timeout-ms:3000}") long rateTimeoutMs) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.carriers = carriers;
        this.rateShopper = new RateShopper(carriers::find, rateTimeoutMs);
        this.tx = new TransactionTemplate(txManager);
        this.json = objectMapper.copy().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    // ================================================================== 建单

    /**
     * 创建出库单。幂等：同一货主 + 来源 + 外部单号重复提交，返回已有订单（duplicate = true）。
     */
    @Transactional
    public OrderView createOrder(long tenantId, CreateOrderRequest req) {
        var own = jdbc.queryForMap(SQL_CHECK_OWNERSHIP, new MapSqlParameterSource()
                .addValue("warehouseId", req.warehouseId()).addValue("customerId", req.customerId()));
        if (!Long.valueOf(tenantId).equals(toLong(own.get("wh_tenant")))
                || !Long.valueOf(tenantId).equals(toLong(own.get("cust_tenant")))) {
            throw new OutboundException(Code.NOT_FOUND, "warehouse or customer not found");
        }
        String source = req.source() == null || req.source().isBlank() ? "API" : req.source().trim().toUpperCase();
        var to = req.shipTo();
        if (isBlank(to.address1()) || isBlank(to.city()) || isBlank(to.state()) || isBlank(to.zip())) {
            throw new OutboundException(Code.INVALID_REQUEST, "shipTo needs address1, city, state and zip");
        }
        if (req.requestedService() != null && !req.requestedService().matches("[A-Z_]+:[A-Z0-9_]+")) {
            throw new OutboundException(Code.INVALID_REQUEST, "requestedService must look like 'UPS:UPS_GROUND'");
        }

        // 先解析 SKU，避免建了单头才发现 SKU 不存在
        List<long[]> lines = new ArrayList<>();      // [skuId, qty]
        for (LineRequest l : req.lines()) {
            List<Long> ids = jdbc.queryForList(SQL_RESOLVE_SKU, new MapSqlParameterSource()
                    .addValue("customerId", req.customerId())
                    .addValue("skuId", l.skuId())
                    .addValue("skuCode", l.skuCode()), Long.class);
            if (ids.size() != 1) {
                throw new OutboundException(Code.INVALID_REQUEST,
                        "unknown SKU " + (l.skuCode() != null ? l.skuCode() : l.skuId()) + " for this customer");
            }
            lines.add(new long[]{ids.get(0), l.qty()});
        }

        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("warehouseId", req.warehouseId())
                .addValue("customerId", req.customerId())
                .addValue("source", source)
                .addValue("externalOrderNo", req.externalOrderNo().trim())
                .addValue("shipTo", toJson(req.shipTo()))
                .addValue("requestedService", req.requestedService())
                .addValue("reference", req.reference());
        List<Long> inserted = jdbc.queryForList(SQL_INSERT_ORDER, params, Long.class);
        if (inserted.isEmpty()) {
            long existing = jdbc.queryForObject(SQL_FIND_ORDER_BY_EXTERNAL, params, Long.class);
            return view(tenantId, existing, true);
        }
        long orderId = inserted.get(0);
        int lineNo = 1;
        for (long[] l : lines) {
            jdbc.update(SQL_INSERT_LINE, new MapSqlParameterSource()
                    .addValue("orderId", orderId).addValue("lineNo", lineNo++)
                    .addValue("skuId", l[0]).addValue("qty", (int) l[1]));
        }
        return view(tenantId, orderId, false);
    }

    // ================================================================== 分配

    /**
     * 为整单分配库存（全部满足才成功，否则整体回滚、订单保持 NEW）。
     * 分配逐个库位尝试；与其他订单并发争抢同一库存时，条件 UPDATE 保证不会超分配。
     */
    @Transactional
    public OrderView allocate(long tenantId, long orderId) {
        Map<String, Object> order = lockOrder(tenantId, orderId, OrderStatus.NEW);
        long warehouseId = toLong(order.get("warehouse_id"));
        long customerId = toLong(order.get("customer_id"));

        // 按 SKU 顺序加锁：两张订单含相同 SKU 但行顺序不同时，避免互相等待造成死锁
        var orderLines = new ArrayList<>(jdbc.queryForList(SQL_ORDER_LINES, p("orderId", orderId)));
        orderLines.sort(java.util.Comparator.comparing((Map<String, Object> l) -> toLong(l.get("sku_id")))
                .thenComparing(l -> toLong(l.get("id"))));
        for (Map<String, Object> line : orderLines) {
            long lineId = toLong(line.get("id"));
            long skuId = toLong(line.get("sku_id"));
            int remaining = ((Number) line.get("qty_ordered")).intValue() - ((Number) line.get("qty_allocated")).intValue();

            var candidates = jdbc.queryForList(SQL_ALLOCATION_CANDIDATES, new MapSqlParameterSource()
                    .addValue("warehouseId", warehouseId).addValue("customerId", customerId).addValue("skuId", skuId));
            for (Map<String, Object> c : candidates) {
                if (remaining == 0) {
                    break;
                }
                int take = Math.min(remaining, ((Number) c.get("available")).intValue());
                var key = new StockKey(warehouseId, toLong(c.get("location_id")), toLong(c.get("lpn_id")),
                        customerId, skuId, toLong(c.get("lot_id")), InventoryStatus.AVAILABLE);
                if (!inventory.tryAllocate(key, take)) {
                    continue;   // 被并发订单抢走，换下一个库位
                }
                jdbc.update(SQL_INSERT_ALLOCATION, new MapSqlParameterSource()
                        .addValue("lineId", lineId).addValue("warehouseId", warehouseId)
                        .addValue("locationId", key.locationId()).addValue("lpnId", key.lpnId())
                        .addValue("customerId", customerId).addValue("skuId", skuId)
                        .addValue("lotId", key.lotId()).addValue("qty", take));
                jdbc.update(SQL_ADD_LINE_ALLOCATED, new MapSqlParameterSource()
                        .addValue("lineId", lineId).addValue("qty", take));
                remaining -= take;
            }
            if (remaining > 0) {
                throw new OutboundException(Code.INSUFFICIENT_STOCK,
                        "not enough pickable stock for sku " + skuId + ": short " + remaining
                                + " (reserve pallets need replenishment to pick locations)");
            }
        }
        setStatus(orderId, OrderStatus.ALLOCATED);
        return view(tenantId, orderId, false);
    }

    // ================================================================== 拣货 / 打包

    /** 整单拣货确认（一期简化；PDA 逐行扫码拣货在后续迭代加入） */
    @Transactional
    public OrderView confirmPick(long tenantId, long orderId) {
        lockOrder(tenantId, orderId, OrderStatus.ALLOCATED);
        jdbc.update(SQL_PICK_ALL_ALLOCATIONS, p("orderId", orderId));
        jdbc.update(SQL_PICK_ALL_LINES, p("orderId", orderId));
        setStatus(orderId, OrderStatus.PICKED);
        return view(tenantId, orderId, false);
    }

    /** 打包称重：每箱的商品合计必须与已拣数量完全一致 */
    @Transactional
    public OrderView pack(long tenantId, long orderId, PackRequest req) {
        lockOrder(tenantId, orderId, OrderStatus.PICKED);

        Map<Long, Integer> picked = new LinkedHashMap<>();
        for (var l : jdbc.queryForList(SQL_ORDER_LINES, p("orderId", orderId))) {
            picked.merge(toLong(l.get("sku_id")), ((Number) l.get("qty_picked")).intValue(), Integer::sum);
        }
        Map<Long, Integer> packed = new LinkedHashMap<>();
        req.packages().forEach(pk -> pk.items().forEach(i -> packed.merge(i.skuId(), i.qty(), Integer::sum)));
        if (!picked.equals(packed)) {
            throw new OutboundException(Code.INVALID_REQUEST,
                    "packed quantities " + packed + " do not match picked quantities " + picked);
        }

        int no = 1;
        for (PackageRequest pk : req.packages()) {
            Long boxId = null;
            BigDecimal l = pk.lengthIn(), w = pk.widthIn(), h = pk.heightIn();
            if (pk.boxType() != null && !pk.boxType().isBlank()) {
                var boxes = jdbc.queryForList(SQL_BOX_TYPE, new MapSqlParameterSource()
                        .addValue("tenantId", tenantId).addValue("code", pk.boxType()));
                if (boxes.isEmpty()) {
                    throw new OutboundException(Code.INVALID_REQUEST, "unknown box type " + pk.boxType());
                }
                var b = boxes.get(0);
                boxId = toLong(b.get("id"));
                l = (BigDecimal) b.get("length_in");
                w = (BigDecimal) b.get("width_in");
                h = (BigDecimal) b.get("height_in");
            }
            if (l == null || w == null || h == null || l.signum() <= 0 || w.signum() <= 0 || h.signum() <= 0) {
                throw new OutboundException(Code.INVALID_REQUEST, "package " + no + " needs a box type or dimensions");
            }
            long packageId = jdbc.queryForObject(SQL_INSERT_PACKAGE, new MapSqlParameterSource()
                    .addValue("orderId", orderId).addValue("packageNo", no++).addValue("boxTypeId", boxId)
                    .addValue("lengthIn", l).addValue("widthIn", w).addValue("heightIn", h)
                    .addValue("weightLb", pk.weightLb()), Long.class);
            Map<Long, Integer> items = new LinkedHashMap<>();
            for (PackItem i : pk.items()) {
                items.merge(i.skuId(), i.qty(), Integer::sum);
            }
            items.forEach((sku, qty) -> jdbc.update(SQL_INSERT_PACKAGE_ITEM, new MapSqlParameterSource()
                    .addValue("packageId", packageId).addValue("skuId", sku).addValue("qty", qty)));
        }
        setStatus(orderId, OrderStatus.PACKED);
        return view(tenantId, orderId, false);
    }

    // ================================================================== 比价

    /** 对已打包订单询价，全部报价存档，返回最优服务 */
    @Transactional
    public RateShopView rateShop(long tenantId, long orderId) {
        Map<String, Object> order = lockOrder(tenantId, orderId, OrderStatus.PACKED);
        var ctx = shippingContext(tenantId, order);
        RateShopper.Result r = rateShopper.shop(ctx.accounts(), ctx.from(), ctx.to(), ctx.parcels(), ctx.rule());
        for (RateQuote q : r.quotes()) {
            jdbc.update(SQL_INSERT_QUOTE, new MapSqlParameterSource()
                    .addValue("orderId", orderId).addValue("carrierCode", q.carrierCode())
                    .addValue("serviceCode", q.serviceCode()).addValue("amount", q.amount())
                    .addValue("currency", q.currency()).addValue("transitDays", q.transitDays())
                    .addValue("selected", q.equals(r.best())));
        }
        return new RateShopView(r.quotes(), r.best(), r.errors());
    }

    // ================================================================== 发货

    /**
     * 打单并发货。分三步，保证“面单已生成但库存未扣”或“库存已扣但没有面单”都不会发生：
     *  1. 事务一：锁订单、确认没有进行中的发运，登记 PENDING 发运记录（防止重复打单）。
     *  2. 调用承运商生成面单（在事务之外，避免长时间持有数据库锁）。
     *  3. 事务二：写入追踪号与运费、按分配扣减库存、订单置为 SHIPPED、记录计费事件。
     *     若事务二失败，自动作废刚生成的面单，并把发运记录标为 VOIDED。
     */
    public ShipView ship(long tenantId, long orderId, ShipRequest req, String operator) {
        // ---------- 步骤 1
        record Prepared(long shipmentId, String orderNo, long warehouseId, long customerId, CarrierAccount account,
                        String serviceCode, Address from, Address to, List<Parcel> parcels, List<Long> packageIds) {
        }
        Prepared prep = tx.execute(status -> {
            Map<String, Object> order = lockOrder(tenantId, orderId, OrderStatus.PACKED);
            Long active = jdbc.queryForObject(SQL_ACTIVE_SHIPMENTS, p("orderId", orderId), Long.class);
            if (active != null && active > 0) {
                throw new OutboundException(Code.INVALID_STATE, "a shipment for this order is already in progress");
            }
            var ctx = shippingContext(tenantId, order);

            String carrier = req == null ? null : req.carrierCode();
            String service = req == null ? null : req.serviceCode();
            if (carrier == null && order.get("requested_service") != null) {
                String[] parts = ((String) order.get("requested_service")).split(":");
                carrier = parts[0];
                service = parts[1];
            }
            if (carrier == null) {
                RateShopper.Result r = rateShopper.shop(ctx.accounts(), ctx.from(), ctx.to(), ctx.parcels(), ctx.rule());
                if (r.best() == null) {
                    throw new OutboundException(Code.NO_RATE, "no eligible rate; carrier errors: " + r.errors());
                }
                carrier = r.best().carrierCode();
                service = r.best().serviceCode();
            }
            final String carrierCode = carrier;
            CarrierAccount account = ctx.accounts().stream().filter(a -> a.carrierCode().equals(carrierCode))
                    .findFirst().orElseThrow(() -> new OutboundException(Code.INVALID_REQUEST,
                            "no active account for carrier " + carrierCode));
            if (!ctx.rule().activeServices().contains(carrier + ":" + service)) {
                throw new OutboundException(Code.INVALID_REQUEST, "service " + carrier + ":" + service + " is not active");
            }
            long shipmentId = jdbc.queryForObject(SQL_INSERT_SHIPMENT, new MapSqlParameterSource()
                    .addValue("orderId", orderId).addValue("carrierCode", carrier)
                    .addValue("serviceCode", service).addValue("accountId", account.id()), Long.class);
            return new Prepared(shipmentId, (String) order.get("order_no"), toLong(order.get("warehouse_id")),
                    toLong(order.get("customer_id")), account, service, ctx.from(), ctx.to(), ctx.parcels(),
                    ctx.packageIds());
        });

        // ---------- 步骤 2
        CarrierAdapter adapter = carriers.find(prep.account().carrierCode()).orElse(null);
        LabelResult labels;
        try {
            if (adapter == null) {
                throw new CarrierException(prep.account().carrierCode(), "no adapter configured (mode=" + carriers.mode() + ")");
            }
            labels = adapter.createLabel(new LabelRequest(prep.account(), prep.serviceCode(), prep.from(), prep.to(),
                    prep.parcels(), prep.orderNo()));
            if (labels.labels().size() != prep.parcels().size()) {
                throw new CarrierException(prep.account().carrierCode(), "label count does not match package count");
            }
        } catch (RuntimeException e) {
            tx.executeWithoutResult(s -> jdbc.update(SQL_SHIPMENT_FAILED, new MapSqlParameterSource()
                    .addValue("shipmentId", prep.shipmentId()).addValue("error", e.getMessage())));
            throw new OutboundException(Code.CARRIER_ERROR, e.getMessage());
        }
        List<String> trackingNos = labels.labels().stream().map(l -> l.trackingNo()).toList();

        // ---------- 步骤 3
        try {
            tx.executeWithoutResult(status -> {
                lockOrder(tenantId, orderId, OrderStatus.PACKED);
                for (int i = 0; i < prep.packageIds().size(); i++) {
                    var l = labels.labels().get(i);
                    jdbc.update(SQL_PACKAGE_LABEL, new MapSqlParameterSource()
                            .addValue("packageId", prep.packageIds().get(i)).addValue("shipmentId", prep.shipmentId())
                            .addValue("trackingNo", l.trackingNo()).addValue("labelFormat", l.labelFormat())
                            .addValue("labelData", l.labelData()).addValue("cost", l.cost()));
                }
                jdbc.update(SQL_SHIPMENT_LABELED, new MapSqlParameterSource()
                        .addValue("shipmentId", prep.shipmentId()).addValue("totalCost", labels.totalCost())
                        .addValue("currency", labels.currency()));

                int units = 0;
                var allocations = jdbc.queryForList(SQL_PICKED_ALLOCATIONS, new MapSqlParameterSource()
                        .addValue("orderId", orderId).addValue("statuses", List.of("PICKED")));
                for (var a : allocations) {
                    long allocationId = toLong(a.get("id"));
                    int qty = ((Number) a.get("qty_picked")).intValue();
                    var key = new StockKey(toLong(a.get("warehouse_id")), toLong(a.get("location_id")),
                            toLong(a.get("lpn_id")), toLong(a.get("customer_id")), toLong(a.get("sku_id")),
                            toLong(a.get("lot_id")), InventoryStatus.AVAILABLE);
                    var ctx = new OpContext(tenantId, operator, "SHIP-ALLOC-" + allocationId, "ORDER",
                            prep.orderNo(), null);
                    inventory.shipAllocated(ctx, key, qty);
                    jdbc.update(SQL_SET_ALLOCATION_STATUS, new MapSqlParameterSource()
                            .addValue("allocationId", allocationId).addValue("status", "SHIPPED"));
                    units += qty;
                }
                jdbc.update(SQL_LINES_SHIPPED, p("orderId", orderId));
                jdbc.update(SQL_ORDER_SHIPPED, p("orderId", orderId));

                billable(tenantId, prep.warehouseId(), prep.customerId(), "ORDER_FULFILLMENT", "ORDER", orderId, BigDecimal.ONE, null);
                billable(tenantId, prep.warehouseId(), prep.customerId(), "PICK_UNIT", "ORDER", orderId, BigDecimal.valueOf(units), null);
                billable(tenantId, prep.warehouseId(), prep.customerId(), "PACKAGE", "ORDER", orderId, BigDecimal.valueOf(prep.packageIds().size()), null);
                billable(tenantId, prep.warehouseId(), prep.customerId(), "SHIPPING_LABEL", "SHIPMENT", prep.shipmentId(), BigDecimal.ONE,
                        labels.totalCost());
            });
        } catch (RuntimeException e) {
            log.error("Ship step 3 failed for order {}; voiding labels {}", orderId, trackingNos, e);
            try {
                adapter.voidLabel(prep.account(), trackingNos);
            } catch (RuntimeException voidError) {
                log.error("VOID FAILED for {} - void manually in carrier portal", trackingNos, voidError);
            }
            tx.executeWithoutResult(s -> jdbc.update(SQL_SHIPMENT_VOIDED, new MapSqlParameterSource()
                    .addValue("shipmentId", prep.shipmentId()).addValue("error", String.valueOf(e.getMessage()))));
            throw e;
        }
        return new ShipView(prep.shipmentId(), prep.account().carrierCode(), prep.serviceCode(), labels.totalCost(),
                trackingNos);
    }

    private void billable(long tenantId, long warehouseId, long customerId, String type, String refType,
                          long refId, BigDecimal qty, BigDecimal cost) {
        jdbc.update(SQL_INSERT_BILLABLE, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("warehouseId", warehouseId)
                .addValue("customerId", customerId).addValue("eventType", type).addValue("refType", refType)
                .addValue("refId", refId).addValue("quantity", qty).addValue("costAmount", cost));
    }

    // ================================================================== 取消

    /** 取消未发货订单：释放全部分配 */
    @Transactional
    public OrderView cancel(long tenantId, long orderId) {
        Map<String, Object> order = lockOrder(tenantId, orderId, null);
        OrderStatus status = OrderStatus.valueOf((String) order.get("status"));
        if (status == OrderStatus.SHIPPED || status == OrderStatus.CANCELLED) {
            throw new OutboundException(Code.INVALID_STATE, "order is " + status);
        }
        var allocations = jdbc.queryForList(SQL_PICKED_ALLOCATIONS, new MapSqlParameterSource()
                .addValue("orderId", orderId).addValue("statuses", List.of("OPEN", "PICKED")));
        for (var a : allocations) {
            var key = new StockKey(toLong(a.get("warehouse_id")), toLong(a.get("location_id")),
                    toLong(a.get("lpn_id")), toLong(a.get("customer_id")), toLong(a.get("sku_id")),
                    toLong(a.get("lot_id")), InventoryStatus.AVAILABLE);
            inventory.deallocate(key, ((Number) a.get("qty")).intValue());
            jdbc.update(SQL_SET_ALLOCATION_STATUS, new MapSqlParameterSource()
                    .addValue("allocationId", a.get("id")).addValue("status", "RELEASED"));
        }
        jdbc.update(SQL_LINES_RELEASED, p("orderId", orderId));
        setStatus(orderId, OrderStatus.CANCELLED);
        return view(tenantId, orderId, false);
    }

    // ================================================================== 查询

    @Transactional(readOnly = true)
    public OrderView get(long tenantId, long orderId) {
        return view(tenantId, orderId, false);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> label(long tenantId, long packageId) {
        var rows = jdbc.queryForList(SQL_LABEL, new MapSqlParameterSource()
                .addValue("packageId", packageId).addValue("tenantId", tenantId));
        if (rows.isEmpty() || rows.get(0).get("label_data") == null) {
            throw new OutboundException(Code.NOT_FOUND, "label for package " + packageId);
        }
        return rows.get(0);
    }

    private OrderView view(long tenantId, long orderId, boolean duplicate) {
        var rows = jdbc.queryForList(SQL_ORDER_VIEW, new MapSqlParameterSource()
                .addValue("orderId", orderId).addValue("tenantId", tenantId));
        if (rows.isEmpty()) {
            throw new OutboundException(Code.NOT_FOUND, "order " + orderId);
        }
        var o = rows.get(0);
        List<LineView> lines = jdbc.query(SQL_LINE_VIEW, p("orderId", orderId), (rs, i) -> new LineView(
                rs.getLong("id"), rs.getInt("line_no"), rs.getLong("sku_id"), rs.getString("sku_code"),
                rs.getInt("qty_ordered"), rs.getInt("qty_allocated"), rs.getInt("qty_picked"),
                rs.getInt("qty_shipped")));
        Map<Long, Map<Long, Integer>> items = new LinkedHashMap<>();
        for (var it : jdbc.queryForList(SQL_PACKAGE_ITEMS, p("orderId", orderId))) {
            items.computeIfAbsent(toLong(it.get("package_id")), k -> new LinkedHashMap<>())
                    .put(toLong(it.get("sku_id")), ((Number) it.get("qty")).intValue());
        }
        List<PackageView> packages = jdbc.query(SQL_PACKAGES, p("orderId", orderId), (rs, i) -> new PackageView(
                rs.getLong("id"), rs.getInt("package_no"), rs.getBigDecimal("length_in"), rs.getBigDecimal("width_in"),
                rs.getBigDecimal("height_in"), rs.getBigDecimal("weight_lb"), rs.getString("tracking_no"),
                rs.getBigDecimal("cost"), items.getOrDefault(rs.getLong("id"), Map.of())));
        return new OrderView(orderId, (String) o.get("order_no"), toLong(o.get("warehouse_id")),
                toLong(o.get("customer_id")), (String) o.get("source"), (String) o.get("external_order_no"),
                OrderStatus.valueOf((String) o.get("status")), parseAddress((String) o.get("ship_to")),
                (String) o.get("requested_service"), lines, packages, toTime(o.get("created_at")),
                toTime(o.get("shipped_at")), duplicate);
    }

    // ================================================================== 内部

    private record ShippingContext(List<CarrierAccount> accounts, Address from, Address to, List<Parcel> parcels,
                                   List<Long> packageIds, RateShopper.Rule rule) {
    }

    private ShippingContext shippingContext(long tenantId, Map<String, Object> order) {
        long orderId = toLong(order.get("id"));
        long customerId = toLong(order.get("customer_id"));

        List<CarrierAccount> accounts = jdbc.query(SQL_CARRIER_ACCOUNTS, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("customerId", customerId), (rs, i) -> new CarrierAccount(
                rs.getLong("id"), rs.getString("carrier_code"), rs.getString("account_no"),
                rs.getString("credentials_ref"), rs.getBoolean("sandbox")));

        var wh = jdbc.queryForMap(SQL_WAREHOUSE_ADDRESS, p("warehouseId", order.get("warehouse_id")));
        Address whAddr = parseAddress((String) wh.get("address"));
        Address from = new Address((String) wh.get("name"), whAddr.company(), whAddr.address1(), whAddr.address2(),
                whAddr.city(), whAddr.state(), whAddr.zip(), whAddr.country(), whAddr.phone(), false);
        Address to = parseAddress((String) order.get("ship_to"));

        List<Long> packageIds = new ArrayList<>();
        List<Parcel> parcels = new ArrayList<>();
        for (var pk : jdbc.queryForList(SQL_PACKAGES, p("orderId", orderId))) {
            packageIds.add(toLong(pk.get("id")));
            parcels.add(new Parcel((BigDecimal) pk.get("length_in"), (BigDecimal) pk.get("width_in"),
                    (BigDecimal) pk.get("height_in"), (BigDecimal) pk.get("weight_lb")));
        }
        if (parcels.isEmpty()) {
            throw new OutboundException(Code.INVALID_STATE, "order has no packages");
        }

        RateShopper.Strategy strategy = RateShopper.Strategy.CHEAPEST;
        Set<String> allowed = null;
        Integer maxDays = null;
        var rules = jdbc.query(SQL_SHIP_RULE, p("customerId", customerId), (rs, i) -> {
            Array arr = rs.getArray("allowed_services");
            Object max = rs.getObject("max_transit_days");
            return new Object[]{rs.getString("strategy"),
                    arr == null ? null : new HashSet<>(Arrays.asList((String[]) arr.getArray())),
                    max == null ? null : ((Number) max).intValue()};
        });
        if (!rules.isEmpty()) {
            strategy = RateShopper.Strategy.valueOf((String) rules.get(0)[0]);
            @SuppressWarnings("unchecked") Set<String> a = (Set<String>) rules.get(0)[1];
            allowed = a;
            maxDays = (Integer) rules.get(0)[2];
        }
        Set<String> active = new HashSet<>(jdbc.queryForList(SQL_ACTIVE_SERVICES, new MapSqlParameterSource(),
                String.class));
        return new ShippingContext(accounts, from, to, parcels, packageIds,
                new RateShopper.Rule(strategy, allowed, maxDays, active));
    }

    /** 锁订单行；expected 不为空时校验当前状态 */
    private Map<String, Object> lockOrder(long tenantId, long orderId, OrderStatus expected) {
        var rows = jdbc.queryForList(SQL_LOCK_ORDER, new MapSqlParameterSource()
                .addValue("orderId", orderId).addValue("tenantId", tenantId));
        if (rows.isEmpty()) {
            throw new OutboundException(Code.NOT_FOUND, "order " + orderId);
        }
        var o = rows.get(0);
        if (expected != null && !expected.name().equals(o.get("status"))) {
            throw new OutboundException(Code.INVALID_STATE,
                    "order " + orderId + " is " + o.get("status") + ", expected " + expected);
        }
        return o;
    }

    private void setStatus(long orderId, OrderStatus status) {
        jdbc.update(SQL_SET_ORDER_STATUS, new MapSqlParameterSource()
                .addValue("orderId", orderId).addValue("status", status.name()));
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Address parseAddress(String text) {
        try {
            return text == null ? null : json.readValue(text, Address.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("bad address json", e);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static MapSqlParameterSource p(String name, Object value) {
        return new MapSqlParameterSource(name, value);
    }

    private static Long toLong(Object v) {
        return v == null ? null : ((Number) v).longValue();
    }

    private static OffsetDateTime toTime(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof OffsetDateTime odt) {
            return odt;
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atOffset(java.time.ZoneOffset.UTC);
        }
        return null;
    }
}
