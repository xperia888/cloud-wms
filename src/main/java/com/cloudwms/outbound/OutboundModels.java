package com.cloudwms.outbound;

import com.cloudwms.carrier.CarrierModels.Address;
import com.cloudwms.carrier.CarrierModels.RateQuote;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class OutboundModels {

    private OutboundModels() {
    }

    public enum OrderStatus { NEW, ALLOCATED, PICKED, PACKED, SHIPPED, CANCELLED }

    // ------------------------------------------------------------------ 请求

    /** 标准出库单（POST /api/v1/outbound-orders）：所有订单来源（Shopify、SAP、CSV…）都先转换成它 */
    public record CreateOrderRequest(
            @NotNull Long warehouseId,
            @NotNull Long customerId,
            String source,
            @NotBlank String externalOrderNo,
            @NotNull @Valid Address shipTo,
            String requestedService,          // 'UPS:UPS_GROUND'；为空走比价
            String reference,
            @NotEmpty @Valid List<LineRequest> lines) {
    }

    /** skuId 与 skuCode 二选一；外部平台通常只知道 skuCode */
    public record LineRequest(Long skuId, String skuCode, @Positive int qty) {
    }

    public record PackRequest(@NotEmpty @Valid List<PackageRequest> packages) {
    }

    /** 箱型代码或尺寸二选一；重量为打包台称重结果 */
    public record PackageRequest(
            String boxType,
            BigDecimal lengthIn, BigDecimal widthIn, BigDecimal heightIn,
            @NotNull @Positive BigDecimal weightLb,
            @NotEmpty @Valid List<PackItem> items) {
    }

    public record PackItem(@NotNull Long skuId, @Positive int qty) {
    }

    /** 为空则依次使用：订单指定服务 → 比价最优 */
    public record ShipRequest(String carrierCode, String serviceCode) {
    }

    // ------------------------------------------------------------------ 返回

    public record OrderView(
            long id,
            String orderNo,
            long warehouseId,
            long customerId,
            String source,
            String externalOrderNo,
            OrderStatus status,
            Address shipTo,
            String requestedService,
            List<LineView> lines,
            List<PackageView> packages,
            OffsetDateTime createdAt,
            OffsetDateTime shippedAt,
            boolean duplicate) {
    }

    public record LineView(long id, int lineNo, long skuId, String skuCode, int qtyOrdered, int qtyAllocated,
                           int qtyPicked, int qtyShipped) {
    }

    public record PackageView(long id, int packageNo, BigDecimal lengthIn, BigDecimal widthIn,
                              BigDecimal heightIn, BigDecimal weightLb, String trackingNo, BigDecimal cost,
                              Map<Long, Integer> items) {
    }

    public record RateShopView(List<RateQuote> quotes, RateQuote best, Map<String, String> errors) {
    }

    public record ShipView(long shipmentId, String carrierCode, String serviceCode, BigDecimal totalCost,
                           List<String> trackingNumbers) {
    }

    // ------------------------------------------------------------------ 异常

    public static class OutboundException extends RuntimeException {
        public enum Code { NOT_FOUND, INVALID_STATE, INVALID_REQUEST, INSUFFICIENT_STOCK, NO_RATE, CARRIER_ERROR }

        private final Code code;

        public OutboundException(Code code, String message) {
            super(message);
            this.code = code;
        }

        public Code getCode() {
            return code;
        }
    }
}
