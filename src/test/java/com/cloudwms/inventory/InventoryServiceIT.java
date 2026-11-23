package com.cloudwms.inventory;

import com.cloudwms.inventory.LpnService.LpnType;
import com.cloudwms.inventory.model.InventoryModels.InventoryStatus;
import com.cloudwms.inventory.model.InventoryModels.OpContext;
import com.cloudwms.inventory.model.InventoryModels.StockKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 库存引擎集成测试：真实 PostgreSQL 16（Testcontainers，需要本机 Docker）。
 * 运行：mvn verify  或  mvn test -Dtest=InventoryServiceIT
 */
@SpringBootTest
@Testcontainers
class InventoryServiceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired InventoryService inventory;
    @Autowired LpnService lpns;
    @Autowired JdbcTemplate jdbc;

    long tenant, wh, locA, locB, customer, sku;

    @BeforeEach
    void seed() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        tenant = id("INSERT INTO tenant(code,name) VALUES (?, 'Test') RETURNING id", "T" + s);
        wh = id("INSERT INTO warehouse(tenant_id,code,name) VALUES (?,?, 'WH') RETURNING id", tenant, "W" + s.substring(0, 4));
        locA = id("INSERT INTO location(warehouse_id,code) VALUES (?, 'A-01') RETURNING id", wh);
        locB = id("INSERT INTO location(warehouse_id,code) VALUES (?, 'B-01') RETURNING id", wh);
        customer = id("INSERT INTO customer(tenant_id,code,name) VALUES (?, 'C1', 'Cust') RETURNING id", tenant);
        sku = id("INSERT INTO sku(tenant_id,customer_id,sku_code,name) VALUES (?,?, 'SKU-1', 'Widget') RETURNING id",
                tenant, customer);
        jdbc.update("INSERT INTO lpn_rule(warehouse_id, prefix, seq_padding, next_seq) VALUES (?, 'LP', 6, 1)", wh);
    }

    private long id(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private OpContext ctx() {
        return new OpContext(tenant, "tester", null, "TEST", null, "test");
    }

    private OpContext ctx(String idem) {
        return new OpContext(tenant, "tester", idem, "TEST", null, "test");
    }

    private StockKey key(long loc, Long lpn) {
        return new StockKey(wh, loc, lpn, customer, sku, null, InventoryStatus.AVAILABLE);
    }

    private int onHand(StockKey k) {
        return inventory.query(tenant, wh, customer, sku, k.locationId(), k.lpnId(), 100).stream()
                .filter(r -> r.status() == k.status() && java.util.Objects.equals(r.lpnId(), k.lpnId()))
                .mapToInt(r -> r.qtyOnHand()).sum();
    }

    @Test
    void receiveMoveAndReconcile() {
        inventory.receive(ctx(), key(locA, null), 100);
        inventory.move(ctx(), key(locA, null), locB, null, 30);

        assertThat(onHand(key(locA, null))).isEqualTo(70);
        assertThat(onHand(key(locB, null))).isEqualTo(30);
        assertThat(inventory.reconcile()).isEmpty();
    }

    @Test
    void cannotOverdrawOrMoveAllocatedStock() {
        inventory.receive(ctx(), key(locA, null), 10);
        inventory.allocate(key(locA, null), 8);

        assertThatThrownBy(() -> inventory.move(ctx(), key(locA, null), locB, null, 3))
                .isInstanceOf(InventoryException.class)
                .extracting(e -> ((InventoryException) e).getCode())
                .isEqualTo(InventoryException.Code.INSUFFICIENT_AVAILABLE);
        // 失败的操作不留下任何流水
        assertThat(inventory.reconcile()).isEmpty();

        inventory.shipAllocated(ctx(), key(locA, null), 8);
        assertThat(onHand(key(locA, null))).isEqualTo(2);
        assertThat(inventory.reconcile()).isEmpty();
    }

    @Test
    void idempotencyKeyPreventsDoubleCounting() {
        var first = inventory.receive(ctx("PDA-123"), key(locA, null), 5);
        var second = inventory.receive(ctx("PDA-123"), key(locA, null), 5);

        assertThat(second.duplicate()).isTrue();
        assertThat(second.groupId()).isEqualTo(first.groupId());
        assertThat(onHand(key(locA, null))).isEqualTo(5);
    }

    @Test
    void concurrentPicksNeverOversell() throws Exception {
        inventory.receive(ctx(), key(locA, null), 50);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 100; i++) {           // 100 个并发请求各扣 1，只有 50 个能成功
            tasks.add(() -> {
                try {
                    inventory.adjust(ctx(), key(locA, null), -1, false);
                    return true;
                } catch (InventoryException e) {
                    return false;
                }
            });
        }
        int ok = 0;
        for (Future<Boolean> f : pool.invokeAll(tasks)) {
            if (f.get()) {
                ok++;
            }
        }
        pool.shutdown();

        assertThat(ok).isEqualTo(50);
        assertThat(onHand(key(locA, null))).isZero();
        assertThat(inventory.reconcile()).isEmpty();
    }

    @Test
    void wholePalletMoveCarriesChildCases() {
        var pallet = lpns.generate(tenant, wh, LpnType.PALLET, locA, customer);
        var box = lpns.generate(tenant, wh, LpnType.CASE, locA, customer);
        jdbc.update("UPDATE lpn SET parent_lpn_id = ? WHERE id = ?", pallet.id(), box.id());
        assertThat(pallet.lpnNo()).startsWith("LP").endsWith("000001");

        inventory.receive(ctx(), key(locA, pallet.id()), 40);
        inventory.receive(ctx(), key(locA, box.id()), 12);

        inventory.moveLpn(ctx(), pallet.id(), locB);

        assertThat(onHand(key(locB, pallet.id()))).isEqualTo(40);
        assertThat(onHand(key(locB, box.id()))).isEqualTo(12);
        assertThat(onHand(key(locA, pallet.id()))).isZero();
        Long boxLoc = jdbc.queryForObject("SELECT location_id FROM lpn WHERE id = ?", Long.class, box.id());
        assertThat(boxLoc).isEqualTo(locB);
        assertThat(inventory.reconcile()).isEmpty();
    }

    @Test
    void lpnStockCannotBeReceivedIntoAnotherLocation() {
        var pallet = lpns.generate(tenant, wh, LpnType.PALLET, locA, customer);
        assertThatThrownBy(() -> inventory.receive(ctx(), key(locB, pallet.id()), 1))
                .isInstanceOf(InventoryException.class)
                .extracting(e -> ((InventoryException) e).getCode())
                .isEqualTo(InventoryException.Code.LPN_CONFLICT);
    }

    @Test
    void transactionLogIsAppendOnly() {
        inventory.receive(ctx(), key(locA, null), 1);
        assertThatThrownBy(() -> jdbc.update("UPDATE inventory_txn SET qty_delta = 99"))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM inventory_txn"))
                .hasMessageContaining("append-only");
    }
}
