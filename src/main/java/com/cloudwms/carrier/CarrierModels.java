package com.cloudwms.carrier;

import java.math.BigDecimal;
import java.util.List;

/** 承运商适配层的统一数据结构：上层业务只认这些类型，不感知具体承运商的 API。 */
public final class CarrierModels {

    private CarrierModels() {
    }

    public record Address(
            String name,
            String company,
            String address1,
            String address2,
            String city,
            String state,
            String zip,
            String country,
            String phone,
            boolean residential) {
    }

    /** 单个包裹：尺寸（英寸）与重量（磅） */
    public record Parcel(BigDecimal lengthIn, BigDecimal widthIn, BigDecimal heightIn, BigDecimal weightLb) {
    }

    /** 承运商账号（凭证由 credentialsRef 指向密钥系统，不在内存中传递明文） */
    public record CarrierAccount(long id, String carrierCode, String accountNo, String credentialsRef,
                                 boolean sandbox) {
    }

    public record RateRequest(CarrierAccount account, Address shipFrom, Address shipTo, List<Parcel> parcels) {
    }

    public record RateQuote(String carrierCode, String serviceCode, BigDecimal amount, String currency,
                            Integer transitDays) {
    }

    public record LabelRequest(CarrierAccount account, String serviceCode, Address shipFrom, Address shipTo,
                               List<Parcel> parcels, String reference) {
    }

    public record PackageLabel(String trackingNo, String labelFormat, String labelData, BigDecimal cost) {
    }

    /** labels 与请求中的 parcels 一一对应、顺序一致 */
    public record LabelResult(List<PackageLabel> labels, BigDecimal totalCost, String currency) {
    }

    /** 承运商调用失败（超时、认证失败、地址无效等），message 原样展示给操作员 */
    public static class CarrierException extends RuntimeException {
        private final String carrierCode;

        public CarrierException(String carrierCode, String message) {
            super(carrierCode + ": " + message);
            this.carrierCode = carrierCode;
        }

        public String getCarrierCode() {
            return carrierCode;
        }
    }
}
