package com.cloudwms.carrier;

import com.cloudwms.carrier.CarrierModels.CarrierAccount;
import com.cloudwms.carrier.CarrierModels.LabelRequest;
import com.cloudwms.carrier.CarrierModels.LabelResult;
import com.cloudwms.carrier.CarrierModels.RateQuote;
import com.cloudwms.carrier.CarrierModels.RateRequest;

import java.util.List;

/**
 * 承运商适配器。每接入一家承运商（UPS、FedEx、USPS、DHL eCommerce，日后 LTL 的 C.H. Robinson、XPO）
 * 只需实现这个接口，出库流程与比价逻辑不用改。
 *
 * 服务代码一律使用系统内统一代码（carrier_service.service_code，如 UPS_GROUND），
 * 与承运商 API 代码（carrier_ref，如 UPS 的 "03"）之间的转换在适配器内部完成。
 */
public interface CarrierAdapter {

    /** 对应 carrier.code，例如 UPS */
    String carrierCode();

    /** 询价：返回该承运商所有可用服务的报价 */
    List<RateQuote> rate(RateRequest request);

    /** 下单打面单：一次请求可含多个包裹（多箱件） */
    LabelResult createLabel(LabelRequest request);

    /** 作废面单（打错、取消订单）。承运商通常只允许在揽收前作废。 */
    void voidLabel(CarrierAccount account, List<String> trackingNumbers);
}
