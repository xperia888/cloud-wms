package com.cloudwms.carrier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 按承运商代码找到适配器。
 *
 * wms.carriers.mode = simulator：UPS / FedEx / USPS 全部使用模拟器（开发、测试、演示）。
 * wms.carriers.mode = live：使用真实适配器。真实适配器在拿到各承运商开发者凭证后接入，
 * 届时只需在此处注册对应实现（例如 UpsRestAdapter），业务代码不变。
 */
@Component
public class CarrierRegistry {

    private final Map<String, CarrierAdapter> adapters = new HashMap<>();
    private final String mode;

    public CarrierRegistry(@Value("${wms.carriers.mode:live}") String mode) {
        this.mode = mode;
        if ("simulator".equalsIgnoreCase(mode)) {
            for (String code : new String[]{"UPS", "FEDEX", "USPS"}) {
                adapters.put(code, new SimulatedCarrierAdapter(code));
            }
        }
        // live：真实适配器尚未接入（见 README「下一步」）
    }

    public Optional<CarrierAdapter> find(String carrierCode) {
        return Optional.ofNullable(adapters.get(carrierCode));
    }

    public String mode() {
        return mode;
    }
}
