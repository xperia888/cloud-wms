-- =====================================================================
-- V2 库存核心：LPN 托盘号、库存余额（实时查询）、库存流水（只增不改）
--
-- 设计原则
--   1. 余额表 inventory_balance 只用于快速查询与并发控制；
--      流水表 inventory_txn 只增不改，是对账与计费的唯一依据。
--   2. 每次库存变动 = 一个流水组（inventory_txn_group）+ 若干条流水，
--      在同一个数据库事务里与余额更新一起提交。
--   3. 数量一律以 SKU 的基本单位（base_uom）记账，整数。
--   4. 防超卖：扣减用条件 UPDATE（可用量足够才更新），不依赖先查后改。
-- =====================================================================

-- ---------- LPN 编号规则（按仓库配置，自有规则） ----------
-- pattern 占位符：{PREFIX} {WH} {SEQ}；例 'LP{WH}{SEQ}' + padding 8 → LPLAX100000001
CREATE TABLE lpn_rule (
    warehouse_id  BIGINT      PRIMARY KEY REFERENCES warehouse(id),
    prefix        VARCHAR(8)  NOT NULL DEFAULT 'LP',
    pattern       VARCHAR(64) NOT NULL DEFAULT '{PREFIX}{WH}{SEQ}',
    seq_padding   INT         NOT NULL DEFAULT 8 CHECK (seq_padding BETWEEN 4 AND 12),
    next_seq      BIGINT      NOT NULL DEFAULT 1 CHECK (next_seq > 0)
);

-- ---------- LPN（托盘 / 箱） ----------
CREATE TABLE lpn (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL REFERENCES tenant(id),
    warehouse_id   BIGINT      NOT NULL REFERENCES warehouse(id),
    lpn_no         VARCHAR(40) NOT NULL,
    lpn_type       VARCHAR(8)  NOT NULL DEFAULT 'PALLET' CHECK (lpn_type IN ('PALLET','CASE')),
    parent_lpn_id  BIGINT      REFERENCES lpn(id),        -- 箱挂在托盘下（父子嵌套）
    location_id    BIGINT      REFERENCES location(id),
    customer_id    BIGINT      REFERENCES customer(id),
    status         VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
                   CHECK (status IN ('ACTIVE','EMPTY','SHIPPED','VOID')),
    source         VARCHAR(10) NOT NULL DEFAULT 'GENERATED'
                   CHECK (source IN ('GENERATED','SCANNED','MIGRATED')),  -- MIGRATED = 旧系统托盘号原样保留
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, lpn_no),
    CHECK (parent_lpn_id IS NULL OR parent_lpn_id <> id)
);
CREATE INDEX idx_lpn_location ON lpn(location_id);
CREATE INDEX idx_lpn_parent   ON lpn(parent_lpn_id);

-- ---------- 库存余额 ----------
-- 一行 = 仓 + 库位 + LPN + 货主 + SKU + 批次 + 库存状态
CREATE TABLE inventory_balance (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      BIGINT      NOT NULL REFERENCES tenant(id),
    warehouse_id   BIGINT      NOT NULL REFERENCES warehouse(id),
    location_id    BIGINT      NOT NULL REFERENCES location(id),
    lpn_id         BIGINT      REFERENCES lpn(id),          -- 散货可为空
    customer_id    BIGINT      NOT NULL REFERENCES customer(id),
    sku_id         BIGINT      NOT NULL REFERENCES sku(id),
    lot_id         BIGINT      REFERENCES lot(id),          -- 非批次管理商品为空
    inv_status     VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE'
                   CHECK (inv_status IN ('AVAILABLE','HOLD','QC','DAMAGED')),
    qty_on_hand    INT         NOT NULL DEFAULT 0,
    qty_allocated  INT         NOT NULL DEFAULT 0,
    version        BIGINT      NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_balance_non_negative CHECK (qty_on_hand >= 0 AND qty_allocated >= 0),
    CONSTRAINT chk_balance_allocated    CHECK (qty_allocated <= qty_on_hand),
    CONSTRAINT uq_balance_key UNIQUE NULLS NOT DISTINCT
        (location_id, lpn_id, customer_id, sku_id, lot_id, inv_status)
);
CREATE INDEX idx_balance_sku      ON inventory_balance(warehouse_id, customer_id, sku_id);
CREATE INDEX idx_balance_lpn      ON inventory_balance(lpn_id);
CREATE INDEX idx_balance_location ON inventory_balance(location_id);

-- ---------- 流水组（一次业务操作） ----------
CREATE TABLE inventory_txn_group (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT      NOT NULL REFERENCES tenant(id),
    group_type       VARCHAR(20) NOT NULL,     -- RECEIVE / MOVE / LPN_MOVE / ADJUST / STATUS_CHANGE / SHIP / MIGRATION ...
    idempotency_key  VARCHAR(100),             -- 同一个 key 重复提交只生效一次（PDA 重扫、API 重推）
    ref_type         VARCHAR(30),              -- 来源单据类型：ASN / ORDER / COUNT / MANUAL ...
    ref_id           VARCHAR(64),
    reason           VARCHAR(200),
    created_by       VARCHAR(64) NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, idempotency_key)
);

-- ---------- 库存流水（只增不改） ----------
CREATE TABLE inventory_txn (
    id             BIGSERIAL PRIMARY KEY,
    group_id       BIGINT      NOT NULL REFERENCES inventory_txn_group(id),
    tenant_id      BIGINT      NOT NULL,
    warehouse_id   BIGINT      NOT NULL,
    txn_type       VARCHAR(20) NOT NULL
                   CHECK (txn_type IN ('RECEIVE','MOVE_OUT','MOVE_IN','ADJUST','COUNT_ADJUST',
                                       'STATUS_OUT','STATUS_IN','SHIP','RETURN_RECEIVE','MIGRATION')),
    location_id    BIGINT      NOT NULL,
    lpn_id         BIGINT,
    customer_id    BIGINT      NOT NULL,
    sku_id         BIGINT      NOT NULL,
    lot_id         BIGINT,
    inv_status     VARCHAR(10) NOT NULL,
    qty_delta      INT         NOT NULL CHECK (qty_delta <> 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_txn_group   ON inventory_txn(group_id);
CREATE INDEX idx_txn_sku     ON inventory_txn(warehouse_id, customer_id, sku_id, created_at);
CREATE INDEX idx_txn_lpn     ON inventory_txn(lpn_id);
CREATE INDEX idx_txn_created ON inventory_txn(created_at);

-- 流水与流水组只允许插入：任何 UPDATE / DELETE 直接报错
CREATE OR REPLACE FUNCTION forbid_modify() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is append-only: % not allowed', TG_TABLE_NAME, TG_OP;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_txn_append_only
    BEFORE UPDATE OR DELETE ON inventory_txn
    FOR EACH ROW EXECUTE FUNCTION forbid_modify();
CREATE TRIGGER trg_txn_group_append_only
    BEFORE UPDATE OR DELETE ON inventory_txn_group
    FOR EACH ROW EXECUTE FUNCTION forbid_modify();

-- ---------- 每日对账：余额 vs 流水累计，应为空结果 ----------
CREATE VIEW v_inventory_reconcile AS
WITH txn_sum AS (
    SELECT location_id, lpn_id, customer_id, sku_id, lot_id, inv_status,
           SUM(qty_delta) AS qty_from_txn
    FROM inventory_txn
    GROUP BY location_id, lpn_id, customer_id, sku_id, lot_id, inv_status
)
SELECT COALESCE(b.location_id, t.location_id) AS location_id,
       COALESCE(b.lpn_id,      t.lpn_id)      AS lpn_id,
       COALESCE(b.customer_id, t.customer_id) AS customer_id,
       COALESCE(b.sku_id,      t.sku_id)      AS sku_id,
       COALESCE(b.lot_id,      t.lot_id)      AS lot_id,
       COALESCE(b.inv_status,  t.inv_status)  AS inv_status,
       COALESCE(b.qty_on_hand, 0)             AS qty_balance,
       COALESCE(t.qty_from_txn, 0)            AS qty_from_txn
FROM inventory_balance b
FULL OUTER JOIN txn_sum t
  ON  b.location_id = t.location_id
  AND b.lpn_id      IS NOT DISTINCT FROM t.lpn_id
  AND b.customer_id = t.customer_id
  AND b.sku_id      = t.sku_id
  AND b.lot_id      IS NOT DISTINCT FROM t.lot_id
  AND b.inv_status  = t.inv_status
WHERE COALESCE(b.qty_on_hand, 0) <> COALESCE(t.qty_from_txn, 0);

-- ---------- 按 SKU 汇总的可用库存（给出库分配与电商库存回写用） ----------
CREATE VIEW v_inventory_available AS
SELECT b.tenant_id, b.warehouse_id, b.customer_id, b.sku_id,
       SUM(b.qty_on_hand)                     AS qty_on_hand,
       SUM(b.qty_allocated)                   AS qty_allocated,
       SUM(b.qty_on_hand - b.qty_allocated)   AS qty_available
FROM inventory_balance b
JOIN location l ON l.id = b.location_id
WHERE b.inv_status = 'AVAILABLE' AND l.status = 'ACTIVE'
GROUP BY b.tenant_id, b.warehouse_id, b.customer_id, b.sku_id;
