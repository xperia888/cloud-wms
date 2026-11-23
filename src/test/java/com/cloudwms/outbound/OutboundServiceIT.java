package com.cloudwms.outbound;

import com.cloudwms.carrier.CarrierModels.Address;
import com.cloudwms.inventory.InventoryService;
import com.cloudwms.outbound.OutboundModels.CreateOrderRequest;
import com.cloudwms.outbound.OutboundModels.LineRequest;
import com.cloudwms.outbound.OutboundModels.OrderStatus;
import com.cloudwms.outbound.OutboundModels.OutboundException;
import com.cloudwms.outbound.OutboundModels.PackItem;
import com.cloudwms.outbound.OutboundModels.PackRequest;
import com.cloudwms.outbound.OutboundModels.PackageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 小包出库端到端：使用 dev 测试数据与承运商模拟器（application-dev.yml）。
 * 运行：mvn verify（需要 Docker）
 */
@SpringBootTest
@ActiveProfiles("dev")
@Testcontainers
class OutboundServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired OutboundService outbound;
    @Autowired InventoryService inventory;
    @Autowired JdbcTemplate jdbc;

    private long id(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private CreateOrderRequest order(String ext, String skuCode, int qty) {
        long wh = id("SELECT id FROM warehouse WHERE code = 'LAX1'");
        long acme = id("SELECT id FROM customer WHERE code = 'ACME'");
        var to = new Address("Sam Ortiz", null, "1 Market St", null, "San Francisco", "CA", "94105", "US",
                "4155550100", true);
        return new CreateOrderRequest(wh, acme, "API", ext, to, null, null,
                List.of(new LineRequest(null, skuCode, qty)));
    }

    @Test
    void parcelOrderEndToEnd() {
        long tenant = id("SELECT id FROM tenant WHERE code = 'SELF'");
        long headlamp = id("SELECT id FROM sku WHERE sku_code = 'ACME-HEADLAMP'");
        String ext = "E2E-" + UUID.randomUUID();

        var created = outbound.createOrder(tenant, order(ext, "ACME-HEADLAMP", 2));
        assertThat(created.status()).isEqualTo(OrderStatus.NEW);
        assertThat(outbound.createOrder(tenant, order(ext, "ACME-HEADLAMP", 2)).duplicate()).isTrue();

        long oid = created.id();
        outbound.allocate(tenant, oid);
        outbound.confirmPick(tenant, oid);
        outbound.pack(tenant, oid, new PackRequest(List.of(new PackageRequest("BOX-S", null, null, null,
                new BigDecimal("1.1"), List.of(new PackItem(headlamp, 2))))));

        var rates = outbound.rateShop(tenant, oid);
        assertThat(rates.best()).isNotNull();
        assertThat(rates.quotes()).hasSizeGreaterThan(3);

        var shipped = outbound.ship(tenant, oid, null, "tester");
        assertThat(shipped.trackingNumbers()).hasSize(1);
        assertThat(shipped.carrierCode()).isEqualTo(rates.best().carrierCode());

        var view = outbound.get(tenant, oid);
        assertThat(view.status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(view.packages().get(0).trackingNo()).isEqualTo(shipped.trackingNumbers().get(0));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billable_event WHERE ref_id IN (?, ?)",
                Integer.class, oid, shipped.shipmentId())).isEqualTo(4);
        assertThat(inventory.reconcile()).isEmpty();

        assertThatThrownBy(() -> outbound.ship(tenant, oid, null, "tester"))
                .isInstanceOf(OutboundException.class);   // 已发货订单不能再次打单
    }

    @Test
    void packMustMatchPickedQuantities() {
        long tenant = id("SELECT id FROM tenant WHERE code = 'SELF'");
        long bottle = id("SELECT id FROM sku WHERE sku_code = 'ACME-BOTTLE-32'");
        long oid = outbound.createOrder(tenant, order("PK-" + UUID.randomUUID(), "ACME-BOTTLE-32", 3)).id();
        outbound.allocate(tenant, oid);
        outbound.confirmPick(tenant, oid);

        assertThatThrownBy(() -> outbound.pack(tenant, oid, new PackRequest(List.of(new PackageRequest("BOX-M",
                null, null, null, new BigDecimal("3.5"), List.of(new PackItem(bottle, 2)))))))
                .isInstanceOf(OutboundException.class)
                .hasMessageContaining("do not match");
    }

    @Test
    void cancelReleasesAllocation() {
        long tenant = id("SELECT id FROM tenant WHERE code = 'SELF'");
        String availSql = "SELECT qty_available FROM v_inventory_available v JOIN sku s ON s.id = v.sku_id "
                + "WHERE s.sku_code = 'ACME-TENT-2P'";
        int before = jdbc.queryForObject(availSql, Integer.class);
        long oid = outbound.createOrder(tenant, order("CX-" + UUID.randomUUID(), "ACME-TENT-2P", 2)).id();
        outbound.allocate(tenant, oid);
        assertThat(jdbc.queryForObject(availSql, Integer.class)).isEqualTo(before - 2);

        outbound.cancel(tenant, oid);
        assertThat(jdbc.queryForObject(availSql, Integer.class)).isEqualTo(before);
        assertThat(outbound.get(tenant, oid).status()).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    void allocationFailsWhenPickLocationsAreShort() {
        long tenant = id("SELECT id FROM tenant WHERE code = 'SELF'");
        long oid = outbound.createOrder(tenant, order("SH-" + UUID.randomUUID(), "ACME-CHAIR", 30)).id();
        assertThatThrownBy(() -> outbound.allocate(tenant, oid))
                .isInstanceOf(OutboundException.class)
                .hasMessageContaining("replenishment");
        assertThat(outbound.get(tenant, oid).status()).isEqualTo(OrderStatus.NEW);
    }
}
