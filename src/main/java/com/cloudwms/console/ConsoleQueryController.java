package com.cloudwms.console;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 控制台只读接口：下拉选项、订单列表、计费事件、订单的发运记录 */
@RestController
@RequestMapping("/api/v1")
public class ConsoleQueryController {

    private final ConsoleQueryService queries;

    public ConsoleQueryController(ConsoleQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/ref/warehouses")
    public List<Map<String, Object>> warehouses(@RequestHeader("X-Tenant-Id") long tenantId) {
        return queries.warehouses(tenantId);
    }

    @GetMapping("/ref/customers")
    public List<Map<String, Object>> customers(@RequestHeader("X-Tenant-Id") long tenantId) {
        return queries.customers(tenantId);
    }

    @GetMapping("/ref/skus")
    public List<Map<String, Object>> skus(@RequestHeader("X-Tenant-Id") long tenantId,
                                          @RequestParam long warehouseId) {
        return queries.skus(tenantId, warehouseId);
    }

    @GetMapping("/ref/box-types")
    public List<Map<String, Object>> boxTypes(@RequestHeader("X-Tenant-Id") long tenantId) {
        return queries.boxTypes(tenantId);
    }

    @GetMapping("/outbound-orders")
    public List<Map<String, Object>> orders(@RequestHeader("X-Tenant-Id") long tenantId,
                                            @RequestParam long warehouseId,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(defaultValue = "100") int limit) {
        return queries.orders(tenantId, warehouseId, status, limit);
    }

    @GetMapping("/outbound-orders/{id}/shipments")
    public List<Map<String, Object>> shipments(@RequestHeader("X-Tenant-Id") long tenantId, @PathVariable long id) {
        return queries.shipments(tenantId, id);
    }

    @GetMapping("/billable-events")
    public List<Map<String, Object>> billableEvents(@RequestHeader("X-Tenant-Id") long tenantId,
                                                    @RequestParam long warehouseId,
                                                    @RequestParam(required = false) Long customerId,
                                                    @RequestParam(defaultValue = "200") int limit) {
        return queries.billableEvents(tenantId, warehouseId, customerId, limit);
    }
}
