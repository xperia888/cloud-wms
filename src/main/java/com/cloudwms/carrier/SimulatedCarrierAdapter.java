package com.cloudwms.carrier;

import com.cloudwms.carrier.CarrierModels.CarrierAccount;
import com.cloudwms.carrier.CarrierModels.CarrierException;
import com.cloudwms.carrier.CarrierModels.LabelRequest;
import com.cloudwms.carrier.CarrierModels.LabelResult;
import com.cloudwms.carrier.CarrierModels.PackageLabel;
import com.cloudwms.carrier.CarrierModels.Parcel;
import com.cloudwms.carrier.CarrierModels.RateQuote;
import com.cloudwms.carrier.CarrierModels.RateRequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 承运商模拟器：在拿到 UPS / FedEx / USPS 真实 API 凭证之前，用它跑通整条出库链路。
 *
 * 价格为模拟值（按区域、计费重量、住宅地址附加费粗略估算），不代表真实协议价。
 * 计费重量 = max(实重, 体积重 L×W×H/139)，向上取整，与主流小包承运商的计算方式一致。
 *
 * 测试钩子：收件邮编为 00000 时模拟“地址无效”错误；邮编为 99999 时模拟超时（睡眠 10 秒）。
 */
public class SimulatedCarrierAdapter implements CarrierAdapter {

    /** 服务定价参数：base 起步价、perLb 每磅、resi 住宅附加费、transitAdd 时效（-1 = 按区域地面时效） */
    private record Svc(String code, double base, double perLb, double resi, int transit,
                       Boolean residentialOnly, double maxLb) {
    }

    private static final Map<String, List<Svc>> SERVICES = Map.of(
            "UPS", List.of(
                    new Svc("UPS_GROUND", 9.20, 0.95, 5.85, -1, null, 150),
                    new Svc("UPS_2DAY", 18.50, 2.60, 5.85, 2, null, 150),
                    new Svc("UPS_NEXT_DAY", 32.00, 4.10, 5.85, 1, null, 150)),
            "FEDEX", List.of(
                    new Svc("FEDEX_GROUND", 9.00, 0.98, 0, -1, Boolean.FALSE, 150),
                    new Svc("FEDEX_HOME", 9.40, 0.98, 0, -1, Boolean.TRUE, 150),
                    new Svc("FEDEX_2DAY", 18.00, 2.65, 5.85, 2, null, 150)),
            "USPS", List.of(
                    new Svc("USPS_GROUND_ADV", 4.60, 1.05, 0, -2, null, 70),
                    new Svc("USPS_PRIORITY", 8.90, 1.60, 0, 3, null, 70)));

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis() % 1_000_000_000L);

    private final String carrierCode;

    public SimulatedCarrierAdapter(String carrierCode) {
        if (!SERVICES.containsKey(carrierCode)) {
            throw new IllegalArgumentException("no simulator for " + carrierCode);
        }
        this.carrierCode = carrierCode;
    }

    @Override
    public String carrierCode() {
        return carrierCode;
    }

    @Override
    public List<RateQuote> rate(RateRequest req) {
        checkHooks(req.shipTo().zip());
        int zone = zone(req.shipFrom().zip(), req.shipTo().zip());
        List<RateQuote> quotes = new ArrayList<>();
        for (Svc s : SERVICES.get(carrierCode)) {
            Double price = price(s, zone, req.shipTo().residential(), req.parcels());
            if (price != null) {
                quotes.add(new RateQuote(carrierCode, s.code(), money(price), "USD", transit(s, zone)));
            }
        }
        return quotes;
    }

    @Override
    public LabelResult createLabel(LabelRequest req) {
        checkHooks(req.shipTo().zip());
        Svc svc = SERVICES.get(carrierCode).stream().filter(s -> s.code().equals(req.serviceCode())).findFirst()
                .orElseThrow(() -> new CarrierException(carrierCode, "unsupported service " + req.serviceCode()));
        int zone = zone(req.shipFrom().zip(), req.shipTo().zip());
        List<PackageLabel> labels = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Parcel p : req.parcels()) {
            Double price = price(svc, zone, req.shipTo().residential(), List.of(p));
            if (price == null) {
                throw new CarrierException(carrierCode, req.serviceCode() + " not available for this package");
            }
            BigDecimal cost = money(price);
            String tracking = trackingNo(req.account());
            labels.add(new PackageLabel(tracking, "ZPL", zpl(req, tracking, p), cost));
            total = total.add(cost);
        }
        return new LabelResult(labels, total, "USD");
    }

    @Override
    public void voidLabel(CarrierAccount account, List<String> trackingNumbers) {
        // 模拟器：作废总是成功
    }

    // ------------------------------------------------------------------ 定价

    private static Double price(Svc s, int zone, boolean residential, List<Parcel> parcels) {
        if (s.residentialOnly() != null && s.residentialOnly() != residential) {
            return null;
        }
        double sum = 0;
        for (Parcel p : parcels) {
            int billable = billableWeight(p);
            if (billable > s.maxLb()) {
                return null;
            }
            double zoneFactor = 1 + 0.12 * (zone - 2);
            sum += s.base() + s.perLb() * billable * zoneFactor + (residential ? s.resi() : 0);
        }
        return sum;
    }

    static int billableWeight(Parcel p) {
        double dim = p.lengthIn().doubleValue() * p.widthIn().doubleValue() * p.heightIn().doubleValue() / 139.0;
        return (int) Math.ceil(Math.max(p.weightLb().doubleValue(), dim));
    }

    /** 粗略区域：两个邮编首位数字之差，映射到 2–8 区 */
    static int zone(String fromZip, String toZip) {
        int a = digit(fromZip), b = digit(toZip);
        return 2 + Math.min(6, Math.abs(a - b));
    }

    private static int transit(Svc s, int zone) {
        int ground = Math.min(5, 1 + zone / 2);
        return switch (s.transit()) {
            case -1 -> ground;
            case -2 -> Math.min(5, ground + 1);
            default -> s.transit();
        };
    }

    private static int digit(String zip) {
        return zip != null && !zip.isEmpty() && Character.isDigit(zip.charAt(0)) ? zip.charAt(0) - '0' : 9;
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private void checkHooks(String zip) {
        if ("00000".equals(zip)) {
            throw new CarrierException(carrierCode, "address validation failed (simulated)");
        }
        if ("99999".equals(zip)) {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ------------------------------------------------------------------ 面单

    private String trackingNo(CarrierAccount account) {
        long n = SEQ.incrementAndGet();
        return switch (carrierCode) {
            case "UPS" -> "1Z" + pad(account.accountNo().replaceAll("[^A-Z0-9]", ""), 6) + "03"
                    + String.format("%08d", n % 100_000_000L);
            case "FEDEX" -> String.format("7%011d", n % 100_000_000_000L);
            default -> String.format("9400%018d", n);
        };
    }

    /** 取前 len 位，不足补 0 */
    private static String pad(String s, int len) {
        return (s + "0".repeat(len)).substring(0, len);
    }

    private String zpl(LabelRequest req, String tracking, Parcel p) {
        var to = req.shipTo();
        return "^XA"
                + "^FO40,40^A0N,40,40^FDSIMULATED " + carrierCode + " - NOT VALID FOR SHIPPING^FS"
                + "^FO40,110^A0N,30,30^FDSERVICE: " + req.serviceCode() + "^FS"
                + "^FO40,170^A0N,30,30^FDSHIP TO: " + safe(to.name()) + "^FS"
                + "^FO40,210^A0N,30,30^FD" + safe(to.address1()) + "^FS"
                + "^FO40,250^A0N,30,30^FD" + safe(to.city()) + ", " + safe(to.state()) + " " + safe(to.zip()) + "^FS"
                + "^FO40,310^A0N,30,30^FDWEIGHT: " + p.weightLb() + " LB  REF: " + safe(req.reference()) + "^FS"
                + "^FO40,380^BCN,120,Y,N,N^FD" + tracking + "^FS"
                + "^XZ";
    }

    /** 去掉 ZPL 控制字符，防止地址文本破坏面单格式 */
    private static String safe(String s) {
        return s == null ? "" : s.replaceAll("[\\^~]", " ");
    }
}
