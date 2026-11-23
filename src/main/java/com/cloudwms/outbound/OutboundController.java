package com.cloudwms.outbound;

import com.cloudwms.outbound.OutboundModels.CreateOrderRequest;
import com.cloudwms.outbound.OutboundModels.OrderView;
import com.cloudwms.outbound.OutboundModels.PackRequest;
import com.cloudwms.outbound.OutboundModels.RateShopView;
import com.cloudwms.outbound.OutboundModels.ShipRequest;
import com.cloudwms.outbound.OutboundModels.ShipView;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 标准出库单 API。所有订单来源（Shopify、SAP、CSV、货主 ERP）最终都调用 POST /api/v1/outbound-orders。
 * 认证接入前，暂以 X-Tenant-Id / X-Operator 请求头传入租户与操作人。
 */
@RestController
@RequestMapping("/api/v1")
public class OutboundController {

    private final OutboundService outbound;

    public OutboundController(OutboundService outbound) {
        this.outbound = outbound;
    }

    /** 新建返回 201；同一外部单号重复提交返回 200 与已有订单（duplicate = true） */
    @PostMapping("/outbound-orders")
    public ResponseEntity<OrderView> create(@RequestHeader("X-Tenant-Id") long tenantId,
                                            @Valid @RequestBody CreateOrderRequest req) {
        OrderView v = outbound.createOrder(tenantId, req);
        return ResponseEntity.status(v.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(v);
    }

    @GetMapping("/outbound-orders/{id}")
    public OrderView get(@RequestHeader("X-Tenant-Id") long tenantId, @PathVariable long id) {
        return outbound.get(tenantId, id);
    }

    @PostMapping("/outbound-orders/{id}/allocate")
    public OrderView allocate(@RequestHeader("X-Tenant-Id") long tenantId, @PathVariable long id) {
        return outbound.allocate(tenantId, id);
    }

    @PostMapping("/outbound-orders/{id}/pick")
    public OrderView pick(@RequestHeader("X-Tenant-Id") long tenantId, @PathVariable long id) {
        return outbound.confirmPick(tenantId, id);
    }

    @PostMapping("/outbound-orders/{id}/pack")
    public OrderView pack(@RequestHeader("X-Tenant-Id") long tenantId, @PathVariable long id,
                          @Valid @RequestBody PackRequest req) {
        return outbound.pack(tenantId, id, req);
    }

    @PostMapping("/outbound-orders/{id}/rates")
    public RateShopView rates(@RequestHeader("X-Tenant-Id") long tenantId, @PathVariable long id) {
        return outbound.rateShop(tenantId, id);
    }

    @PostMapping("/outbound-orders/{id}/ship")
    public ShipView ship(@RequestHeader("X-Tenant-Id") long tenantId,
                         @RequestHeader("X-Operator") String operator,
                         @PathVariable long id,
                         @RequestBody(required = false) ShipRequest req) {
        return outbound.ship(tenantId, id, req, operator);
    }

    @PostMapping("/outbound-orders/{id}/cancel")
    public OrderView cancel(@RequestHeader("X-Tenant-Id") long tenantId, @PathVariable long id) {
        return outbound.cancel(tenantId, id);
    }

    /** 面单内容（ZPL 可直接发送到 Zebra 等热敏打印机） */
    @GetMapping("/packages/{packageId}/label")
    public ResponseEntity<String> label(@RequestHeader("X-Tenant-Id") long tenantId, @PathVariable long packageId) {
        var l = outbound.label(tenantId, packageId);
        return ResponseEntity.ok().contentType(MediaType.TEXT_PLAIN).body((String) l.get("label_data"));
    }
}
