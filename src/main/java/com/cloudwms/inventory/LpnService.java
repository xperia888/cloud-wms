package com.cloudwms.inventory;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * LPN（License Plate Number，托盘号 / 箱号）管理。
 *
 *  - 编号采用自有规则：按仓库在 lpn_rule 配置前缀、格式与流水号位数，例如 LP + LAX1 + 00000001。
 *  - 流水号用 UPDATE ... RETURNING 原子递增，多台 PDA 同时收货也不会撞号。
 *  - 旧系统托盘号迁移时以 source = MIGRATED 原样登记；新号段的起始值应设在旧号之后以免冲突，
 *    即便撞号，(tenant_id, lpn_no) 唯一约束也会拒绝写入。
 */
@Service
public class LpnService {

    public static final String SQL_NEXT_SEQ = """
            UPDATE lpn_rule r
               SET next_seq = r.next_seq + 1
              FROM warehouse w
             WHERE r.warehouse_id = :warehouseId AND w.id = r.warehouse_id
            RETURNING r.prefix, r.pattern, r.seq_padding, r.next_seq - 1 AS seq, w.code AS wh_code,
                      w.tenant_id""";

    public static final String SQL_INSERT_LPN = """
            INSERT INTO lpn (tenant_id, warehouse_id, lpn_no, lpn_type, location_id, customer_id, source)
            VALUES (:tenantId, :warehouseId, :lpnNo, :lpnType, :locationId, :customerId, :source)
            RETURNING id""";

    /** 取 LPN 及其所有子 LPN（递归），并加行锁 */
    public static final String SQL_LOCK_LPN_TREE = """
            WITH RECURSIVE tree AS (
                SELECT id FROM lpn WHERE id = :lpnId
                UNION ALL
                SELECT c.id FROM lpn c JOIN tree t ON c.parent_lpn_id = t.id
            )
            SELECT l.id, l.tenant_id, l.warehouse_id, l.location_id, l.status
              FROM lpn l JOIN tree t ON t.id = l.id
             ORDER BY l.id
               FOR UPDATE OF l""";

    public static final String SQL_SET_LPN_LOCATION = """
            UPDATE lpn SET location_id = :locationId, updated_at = now() WHERE id IN (:ids)""";

    public enum LpnType { PALLET, CASE }

    public enum LpnSource { GENERATED, SCANNED, MIGRATED }

    public record Lpn(long id, String lpnNo) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public LpnService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 按仓库规则生成新的 LPN 号并登记 */
    @Transactional
    public Lpn generate(long tenantId, long warehouseId, LpnType type, Long locationId, Long customerId) {
        var rows = jdbc.queryForList(SQL_NEXT_SEQ, new MapSqlParameterSource("warehouseId", warehouseId));
        if (rows.isEmpty() || ((Number) rows.get(0).get("tenant_id")).longValue() != tenantId) {
            // 别的租户的仓库：回滚刚才的流水号递增，按不存在处理
            throw InventoryException.notFound("LPN rule for warehouse " + warehouseId);
        }
        Map<String, Object> r = rows.get(0);
        String lpnNo = format((String) r.get("pattern"), (String) r.get("prefix"), (String) r.get("wh_code"),
                ((Number) r.get("seq")).longValue(), ((Number) r.get("seq_padding")).intValue());
        long id = insert(tenantId, warehouseId, lpnNo, type, locationId, customerId, LpnSource.GENERATED);
        return new Lpn(id, lpnNo);
    }

    /** 登记一个已有编号的 LPN（扫描到的外来托标，或迁移的旧系统托盘号） */
    @Transactional
    public Lpn register(long tenantId, long warehouseId, String lpnNo, LpnType type,
                       Long locationId, Long customerId, LpnSource source) {
        if (lpnNo == null || lpnNo.isBlank()) {
            throw InventoryException.invalid("lpnNo is required");
        }
        long id = insert(tenantId, warehouseId, lpnNo.trim(), type, locationId, customerId, source);
        return new Lpn(id, lpnNo.trim());
    }

    /** 锁定 LPN 及子 LPN，返回 id 列表（第一个为根） */
    List<Map<String, Object>> lockTree(long lpnId) {
        return jdbc.queryForList(SQL_LOCK_LPN_TREE, new MapSqlParameterSource("lpnId", lpnId));
    }

    void setLocation(List<Long> ids, long locationId) {
        jdbc.update(SQL_SET_LPN_LOCATION, new MapSqlParameterSource()
                .addValue("ids", ids)
                .addValue("locationId", locationId));
    }

    private long insert(long tenantId, long warehouseId, String lpnNo, LpnType type,
                        Long locationId, Long customerId, LpnSource source) {
        var params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("warehouseId", warehouseId)
                .addValue("lpnNo", lpnNo)
                .addValue("lpnType", type.name())
                .addValue("locationId", locationId)
                .addValue("customerId", customerId)
                .addValue("source", source.name());
        return jdbc.queryForObject(SQL_INSERT_LPN, params, Long.class);
    }

    static String format(String pattern, String prefix, String whCode, long seq, int padding) {
        String seqText = String.format("%0" + padding + "d", seq);
        return pattern.replace("{PREFIX}", prefix)
                .replace("{WH}", whCode)
                .replace("{SEQ}", seqText);
    }
}
