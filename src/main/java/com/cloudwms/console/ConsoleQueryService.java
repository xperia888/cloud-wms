package com.cloudwms.console;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 控制台（管理后台）用的只读查询：列表、下拉选项、计费事件。
 * 不改任何数据；写操作仍然走 InventoryService / OutboundService。
 */
@Service
@Transactional(readOnly = true)
public class ConsoleQueryService {

    public static final String SQL_WAREHOUSES = """
            SELECT id, code, name FROM warehouse
             WHERE tenant_id = :tenantId AND active ORDER BY code""";

    public static final String SQL_CUSTOMERS = """
            SELECT id, code, name FROM customer
             WHERE tenant_id = :tenantId AND active ORDER BY code""";

    /** SKU 及其在该仓的库存：qty_pickable = 可拣库位上的可用量（建单时能直接分配的数量） */
    public static final String SQL_SKUS = """
            SELECT s.id, s.customer_id, s.sku_code, s.name, s.weight_lb, s.lot_controlled,
                   COALESCE(SUM(b.qty_on_hand), 0)                                   AS qty_on_hand,
                   COALESCE(SUM(b.qty_on_hand - b.qty_allocated)
                            FILTER (WHERE b.inv_status = 'AVAILABLE'), 0)            AS qty_available,
                   COALESCE(SUM(b.qty_on_hand - b.qty_allocated)
                            FILTER (WHERE b.inv_status = 'AVAILABLE' AND l.pickable
                                      AND l.status = 'ACTIVE'), 0)                   AS qty_pickable
              FROM sku s
              LEFT JOIN inventory_balance b ON b.sku_id = s.id AND b.warehouse_id = :warehouseId
              LEFT JOIN location l ON l.id = b.location_id
             WHERE s.tenant_id = :tenantId AND s.active
             GROUP BY s.id
             ORDER BY s.sku_code""";

    public static final String SQL_BOX_TYPES = """
            SELECT code, length_in, width_in, height_in, tare_lb FROM box_type
             WHERE tenant_id = :tenantId AND active
             ORDER BY length_in * width_in * height_in""";

    public static final String SQL_ORDERS = """
            SELECT o.id, o.order_no, o.customer_id, c.code AS customer_code, o.source, o.external_order_no,
                   o.status, o.ship_to->>'name' AS ship_to_name, o.ship_to->>'city' AS ship_to_city,
                   o.ship_to->>'state' AS ship_to_state, o.created_at, o.shipped_at,
                   (SELECT COALESCE(SUM(qty_ordered), 0) FROM order_line WHERE order_id = o.id) AS units,
                   sh.carrier_code, sh.service_code, sh.total_cost,
                   (SELECT string_agg(tracking_no, ',' ORDER BY package_no)
                      FROM package WHERE order_id = o.id AND tracking_no IS NOT NULL) AS tracking_numbers
              FROM sales_order o
              JOIN customer c ON c.id = o.customer_id
              LEFT JOIN LATERAL (
                    SELECT carrier_code, service_code, total_cost FROM shipment
                     WHERE order_id = o.id AND status = 'LABELED' ORDER BY id DESC LIMIT 1) sh ON TRUE
             WHERE o.tenant_id = :tenantId AND o.warehouse_id = :warehouseId
               AND (CAST(:status AS VARCHAR) IS NULL OR o.status = CAST(:status AS VARCHAR))
             ORDER BY o.id DESC
             LIMIT :limit""";

    public static final String SQL_BILLABLE_EVENTS = """
            SELECT e.id, e.customer_id, c.code AS customer_code, e.event_type, e.ref_type, e.ref_id,
                   e.quantity, e.cost_amount, e.occurred_at, e.invoice_id,
                   COALESCE(o.order_no, so.order_no) AS order_no
              FROM billable_event e
              JOIN customer c ON c.id = e.customer_id
              LEFT JOIN sales_order o ON e.ref_type = 'ORDER' AND o.id = e.ref_id
              LEFT JOIN shipment sh   ON e.ref_type = 'SHIPMENT' AND sh.id = e.ref_id
              LEFT JOIN sales_order so ON so.id = sh.order_id
             WHERE e.tenant_id = :tenantId AND e.warehouse_id = :warehouseId
               AND (CAST(:customerId AS BIGINT) IS NULL OR e.customer_id = CAST(:customerId AS BIGINT))
             ORDER BY e.id DESC
             LIMIT :limit""";

    public static final String SQL_SHIPMENTS = """
            SELECT sh.id, sh.carrier_code, sh.service_code, sh.status, sh.total_cost, sh.currency, sh.error,
                   sh.created_at, sh.labeled_at, cs.name AS service_name
              FROM shipment sh
              JOIN sales_order o ON o.id = sh.order_id
              LEFT JOIN carrier_service cs ON cs.carrier_code = sh.carrier_code AND cs.service_code = sh.service_code
             WHERE sh.order_id = :orderId AND o.tenant_id = :tenantId
             ORDER BY sh.id DESC""";

    private final NamedParameterJdbcTemplate jdbc;

    public ConsoleQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> warehouses(long tenantId) {
        return jdbc.queryForList(SQL_WAREHOUSES, new MapSqlParameterSource("tenantId", tenantId));
    }

    public List<Map<String, Object>> customers(long tenantId) {
        return jdbc.queryForList(SQL_CUSTOMERS, new MapSqlParameterSource("tenantId", tenantId));
    }

    public List<Map<String, Object>> skus(long tenantId, long warehouseId) {
        return jdbc.queryForList(SQL_SKUS, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("warehouseId", warehouseId));
    }

    public List<Map<String, Object>> boxTypes(long tenantId) {
        return jdbc.queryForList(SQL_BOX_TYPES, new MapSqlParameterSource("tenantId", tenantId));
    }

    public List<Map<String, Object>> orders(long tenantId, long warehouseId, String status, int limit) {
        return jdbc.queryForList(SQL_ORDERS, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("warehouseId", warehouseId)
                .addValue("status", status == null || status.isBlank() ? null : status)
                .addValue("limit", clamp(limit)));
    }

    public List<Map<String, Object>> billableEvents(long tenantId, long warehouseId, Long customerId, int limit) {
        return jdbc.queryForList(SQL_BILLABLE_EVENTS, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("warehouseId", warehouseId)
                .addValue("customerId", customerId).addValue("limit", clamp(limit)));
    }

    public List<Map<String, Object>> shipments(long tenantId, long orderId) {
        return jdbc.queryForList(SQL_SHIPMENTS, new MapSqlParameterSource()
                .addValue("tenantId", tenantId).addValue("orderId", orderId));
    }

    private static int clamp(int limit) {
        return Math.min(Math.max(limit, 1), 500);
    }
}
