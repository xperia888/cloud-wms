package com.cloudwms.carrier;

import com.cloudwms.carrier.CarrierModels.Address;
import com.cloudwms.carrier.CarrierModels.CarrierAccount;
import com.cloudwms.carrier.CarrierModels.Parcel;
import com.cloudwms.carrier.CarrierModels.RateQuote;
import com.cloudwms.carrier.CarrierModels.RateRequest;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Rate Shopping：向所有可用承运商并发询价，按货主规则过滤后选出最优服务。
 *
 *  - 并发：每家承运商一个虚拟线程，总耗时 ≈ 最慢那家，而不是各家相加。
 *  - 降级：某家超时或报错只记录到 errors，不影响其他承运商，也不阻塞打单。
 *  - 过滤：货主允许的服务、最长送达天数、系统内已停用的服务。
 *  - 排序：CHEAPEST = 价格优先、同价比时效；FASTEST = 时效优先、同时效比价格。
 *
 * 不依赖 Spring 与数据库，便于单独测试；账号、规则由调用方（出库服务）从数据库读取后传入。
 */
public class RateShopper {

    public enum Strategy { CHEAPEST, FASTEST }

    public record Rule(Strategy strategy, Set<String> allowedServices, Integer maxTransitDays,
                       Set<String> activeServices) {
        /** allowedServices / activeServices 元素格式 'UPS:UPS_GROUND'；allowedServices 为空 = 不限 */
        boolean accepts(RateQuote q) {
            String key = q.carrierCode() + ":" + q.serviceCode();
            if (activeServices != null && !activeServices.contains(key)) {
                return false;
            }
            if (allowedServices != null && !allowedServices.isEmpty() && !allowedServices.contains(key)) {
                return false;
            }
            return maxTransitDays == null || (q.transitDays() != null && q.transitDays() <= maxTransitDays);
        }
    }

    public record Result(List<RateQuote> quotes, RateQuote best, Map<String, String> errors) {
    }

    private static final ExecutorService EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final Function<String, java.util.Optional<CarrierAdapter>> adapterLookup;
    private final long timeoutMs;

    public RateShopper(Function<String, java.util.Optional<CarrierAdapter>> adapterLookup, long timeoutMs) {
        this.adapterLookup = adapterLookup;
        this.timeoutMs = timeoutMs;
    }

    public Result shop(List<CarrierAccount> accounts, Address from, Address to, List<Parcel> parcels, Rule rule) {
        Map<String, CompletableFuture<List<RateQuote>>> calls = new LinkedHashMap<>();
        Map<String, String> errors = new LinkedHashMap<>();

        for (CarrierAccount acct : accounts) {
            var adapter = adapterLookup.apply(acct.carrierCode());
            if (adapter.isEmpty()) {
                errors.put(acct.carrierCode(), "no adapter configured");
                continue;
            }
            var req = new RateRequest(acct, from, to, parcels);
            calls.put(acct.carrierCode(), CompletableFuture
                    .supplyAsync(() -> adapter.get().rate(req), EXECUTOR)
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS));
        }

        List<RateQuote> all = new ArrayList<>();
        calls.forEach((carrier, f) -> {
            try {
                all.addAll(f.join());
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                errors.put(carrier, cause instanceof java.util.concurrent.TimeoutException
                        ? "timeout after " + timeoutMs + " ms" : String.valueOf(cause.getMessage()));
            }
        });

        Comparator<RateQuote> byPrice = Comparator.comparing(RateQuote::amount);
        Comparator<RateQuote> byTransit = Comparator.comparing(q -> q.transitDays() == null ? 99 : q.transitDays());
        Comparator<RateQuote> order = rule.strategy() == Strategy.FASTEST
                ? byTransit.thenComparing(byPrice) : byPrice.thenComparing(byTransit);

        all.sort(order);
        RateQuote best = all.stream().filter(rule::accepts).findFirst().orElse(null);
        return new Result(all, best, errors);
    }
}
