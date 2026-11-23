package com.cloudwms.inventory.api;

import com.cloudwms.inventory.InventoryService;
import com.cloudwms.inventory.LpnService;
import com.cloudwms.inventory.LpnService.LpnSource;
import com.cloudwms.inventory.LpnService.LpnType;
import com.cloudwms.inventory.model.InventoryModels.BalanceRow;
import com.cloudwms.inventory.model.InventoryModels.InventoryStatus;
import com.cloudwms.inventory.model.InventoryModels.OpContext;
import com.cloudwms.inventory.model.InventoryModels.OpResult;
import com.cloudwms.inventory.model.InventoryModels.ReconcileDiff;
import com.cloudwms.inventory.model.InventoryModels.StockKey;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 库存核心 REST 接口（内部 / PDA 使用）。
 *
 * 认证尚未接入：暂以请求头 X-Tenant-Id、X-Operator 传入租户与操作人，接入 OAuth 后从令牌中读取。
 * 所有写操作都建议带 Idempotency-Key 请求头，PDA 网络抖动重发时不会重复记账。
 */
@RestController
@RequestMapping("/api/v1")
public class InventoryController {

    private final InventoryService inventory;
    private final LpnService lpns;

    public InventoryController(InventoryService inventory, LpnService lpns) {
        this.inventory = inventory;
        this.lpns = lpns;
    }

    // ------------------------------------------------------------------ 请求体

    public record KeyDto(
            @NotNull Long warehouseId,
            @NotNull Long locationId,
            Long lpnId,
            @NotNull Long customerId,
            @NotNull Long skuId,
            Long lotId,
            InventoryStatus status) {

        StockKey toKey() {
            return new StockKey(warehouseId, locationId, lpnId, customerId, skuId, lotId, status);
        }
    }

    public record QtyRequest(@NotNull @Valid KeyDto key, @Positive int qty, String refType, String refId) {
    }

    public record MoveRequest(@NotNull @Valid KeyDto from, @NotNull Long toLocationId, Long toLpnId,
                              @Positive int qty) {
    }

    public record AdjustRequest(@NotNull @Valid KeyDto key, int delta, boolean countAdjust,
                                @NotBlank String reason) {
    }

    public record StatusRequest(@NotNull @Valid KeyDto key, @NotNull InventoryStatus newStatus,
                                @Positive int qty, String reason) {
    }

    public record LpnCreateRequest(@NotNull Long warehouseId, LpnType type, Long locationId, Long customerId,
                                   String lpnNo, LpnSource source) {
    }

    public record LpnMoveRequest(@NotNull Long toLocationId) {
    }

    // ------------------------------------------------------------------ 写操作

    @PostMapping("/inventory/receive")
    public OpResult receive(@RequestHeader("X-Tenant-Id") long tenantId,
                            @RequestHeader("X-Operator") String operator,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                            @Valid @RequestBody QtyRequest req) {
        var ctx = new OpContext(tenantId, operator, idem, req.refType(), req.refId(), null);
        return inventory.receive(ctx, req.key().toKey(), req.qty());
    }

    @PostMapping("/inventory/move")
    public OpResult move(@RequestHeader("X-Tenant-Id") long tenantId,
                         @RequestHeader("X-Operator") String operator,
                         @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                         @Valid @RequestBody MoveRequest req) {
        var ctx = new OpContext(tenantId, operator, idem, "MANUAL", null, null);
        return inventory.move(ctx, req.from().toKey(), req.toLocationId(), req.toLpnId(), req.qty());
    }

    @PostMapping("/inventory/adjust")
    public OpResult adjust(@RequestHeader("X-Tenant-Id") long tenantId,
                           @RequestHeader("X-Operator") String operator,
                           @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                           @Valid @RequestBody AdjustRequest req) {
        var ctx = new OpContext(tenantId, operator, idem, req.countAdjust() ? "COUNT" : "MANUAL", null, req.reason());
        return inventory.adjust(ctx, req.key().toKey(), req.delta(), req.countAdjust());
    }

    @PostMapping("/inventory/status-change")
    public OpResult changeStatus(@RequestHeader("X-Tenant-Id") long tenantId,
                                 @RequestHeader("X-Operator") String operator,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                                 @Valid @RequestBody StatusRequest req) {
        var ctx = new OpContext(tenantId, operator, idem, "MANUAL", null, req.reason());
        return inventory.changeStatus(ctx, req.key().toKey(), req.newStatus(), req.qty());
    }

    @PostMapping("/lpns")
    public LpnService.Lpn createLpn(@RequestHeader("X-Tenant-Id") long tenantId,
                                    @Valid @RequestBody LpnCreateRequest req) {
        LpnType type = req.type() == null ? LpnType.PALLET : req.type();
        if (req.lpnNo() == null || req.lpnNo().isBlank()) {
            return lpns.generate(tenantId, req.warehouseId(), type, req.locationId(), req.customerId());
        }
        LpnSource source = req.source() == null ? LpnSource.SCANNED : req.source();
        return lpns.register(tenantId, req.warehouseId(), req.lpnNo(), type, req.locationId(), req.customerId(),
                source);
    }

    @PostMapping("/lpns/{lpnId}/move")
    public OpResult moveLpn(@RequestHeader("X-Tenant-Id") long tenantId,
                            @RequestHeader("X-Operator") String operator,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idem,
                            @PathVariable long lpnId,
                            @Valid @RequestBody LpnMoveRequest req) {
        var ctx = new OpContext(tenantId, operator, idem, "MANUAL", null, null);
        return inventory.moveLpn(ctx, lpnId, req.toLocationId());
    }

    // ------------------------------------------------------------------ 查询

    @GetMapping("/inventory")
    public List<BalanceRow> query(@RequestHeader("X-Tenant-Id") long tenantId,
                                  @RequestParam long warehouseId,
                                  @RequestParam(required = false) Long customerId,
                                  @RequestParam(required = false) Long skuId,
                                  @RequestParam(required = false) Long locationId,
                                  @RequestParam(required = false) Long lpnId,
                                  @RequestParam(defaultValue = "200") int limit) {
        return inventory.query(tenantId, warehouseId, customerId, skuId, locationId, lpnId, limit);
    }

    @GetMapping("/inventory/reconcile")
    public List<ReconcileDiff> reconcile() {
        return inventory.reconcile();
    }
}
