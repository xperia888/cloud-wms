-- =====================================================================
-- V3 小包出库：承运商、服务、箱型、出库单、分配、包裹、发运、报价、计费事件
-- LTL 一期为手工流程，表结构在后续迁移中加入（托盘、BOL）。
-- =====================================================================

-- ---------- 承运商 ----------
CREATE TABLE carrier (
    code    VARCHAR(16)  PRIMARY KEY,             -- UPS / FEDEX / USPS / DHL_ECOM
    name    VARCHAR(100) NOT NULL,
    mode    VARCHAR(8)   NOT NULL DEFAULT 'PARCEL' CHECK (mode IN ('PARCEL','LTL')),
    active  BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE carrier_service (
    carrier_code     VARCHAR(16)  NOT NULL REFERENCES carrier(code),
    service_code     VARCHAR(32)  NOT NULL,       -- 系统内统一代码，例 UPS_GROUND
    carrier_ref      VARCHAR(32),                 -- 承运商 API 内的服务代码，例 UPS '03'
    name             VARCHAR(100) NOT NULL,
    transit_days_min INT,
    transit_days_max INT,
    active           BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (carrier_code, service_code)
);

-- 承运商账号：customer_id 为空 = 仓库自有账号（运费加价后转嫁给货主）；
-- 非空 = 货主自带账号（BYOA），只收操作费。
-- 凭证本身不入库，credentials_ref 指向密钥管理系统（AWS Secrets Manager / KMS）中的条目名。
CREATE TABLE carrier_account (
    id               BIGSERIAL PRIMARY KEY,
    tenant_id        BIGINT      NOT NULL REFERENCES tenant(id),
    carrier_code     VARCHAR(16) NOT NULL REFERENCES carrier(code),
    customer_id      BIGINT      REFERENCES customer(id),
    account_no       VARCHAR(64) NOT NULL,
    credentials_ref  VARCHAR(200),
    sandbox          BOOLEAN     NOT NULL DEFAULT TRUE,
    active           BOOLEAN     NOT NULL DEFAULT TRUE,
    UNIQUE (tenant_id, carrier_code, account_no)
);

-- 货主的发运规则：允许的承运商 / 服务、比价策略、运费加价
CREATE TABLE ship_rule (
    id                  BIGSERIAL PRIMARY KEY,
    tenant_id           BIGINT      NOT NULL REFERENCES tenant(id),
    customer_id         BIGINT      NOT NULL REFERENCES customer(id) UNIQUE,
    strategy            VARCHAR(12) NOT NULL DEFAULT 'CHEAPEST' CHECK (strategy IN ('CHEAPEST','FASTEST')),
    allowed_services    TEXT[],                    -- 为空 = 全部启用的服务；元素格式 'UPS:UPS_GROUND'
    max_transit_days    INT,
    markup_pct          NUMERIC(6,2) NOT NULL DEFAULT 0,   -- 运费加价百分比（月结时使用）
    markup_flat         NUMERIC(10,2) NOT NULL DEFAULT 0
);

-- ---------- 箱型 ----------
CREATE TABLE box_type (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT        NOT NULL REFERENCES tenant(id),
    code        VARCHAR(32)   NOT NULL,
    length_in   NUMERIC(10,2) NOT NULL,
    width_in    NUMERIC(10,2) NOT NULL,
    height_in   NUMERIC(10,2) NOT NULL,
    tare_lb     NUMERIC(10,3) NOT NULL DEFAULT 0,
    active      BOOLEAN       NOT NULL DEFAULT TRUE,
    UNIQUE (tenant_id, code)
);

-- ---------- 出库单 ----------
CREATE TABLE sales_order (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          BIGINT      NOT NULL REFERENCES tenant(id),
    warehouse_id       BIGINT      NOT NULL REFERENCES warehouse(id),
    customer_id        BIGINT      NOT NULL REFERENCES customer(id),
    order_no           VARCHAR(40) NOT NULL,              -- WMS 内部单号
    source             VARCHAR(32) NOT NULL DEFAULT 'API', -- API / SHOPIFY / CSV / SAP ...
    external_order_no  VARCHAR(64) NOT NULL,              -- 来源平台单号（幂等键）
    ship_mode          VARCHAR(8)  NOT NULL DEFAULT 'PARCEL' CHECK (ship_mode IN ('PARCEL','LTL')),
    status             VARCHAR(12) NOT NULL DEFAULT 'NEW'
                       CHECK (status IN ('NEW','ALLOCATED','PICKED','PACKED','SHIPPED','CANCELLED')),
    ship_to            JSONB       NOT NULL,              -- name, company, address1, address2, city, state, zip, country, phone, residential
    requested_service  VARCHAR(48),                       -- 货主指定服务 'UPS:UPS_GROUND'；为空走比价
    reference          VARCHAR(100),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    shipped_at         TIMESTAMPTZ,
    UNIQUE (tenant_id, order_no),
    UNIQUE (customer_id, source, external_order_no)
);
CREATE INDEX idx_order_status ON sales_order(warehouse_id, status, created_at);

CREATE TABLE order_line (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT NOT NULL REFERENCES sales_order(id),
    line_no        INT    NOT NULL,
    sku_id         BIGINT NOT NULL REFERENCES sku(id),
    qty_ordered    INT    NOT NULL CHECK (qty_ordered > 0),
    qty_allocated  INT    NOT NULL DEFAULT 0,
    qty_picked     INT    NOT NULL DEFAULT 0,
    qty_shipped    INT    NOT NULL DEFAULT 0,
    UNIQUE (order_id, line_no),
    CHECK (qty_allocated <= qty_ordered AND qty_picked <= qty_allocated AND qty_shipped <= qty_picked)
);

-- 分配明细：订单行从哪个库存键拿多少（对应 inventory_balance.qty_allocated）
CREATE TABLE allocation (
    id             BIGSERIAL PRIMARY KEY,
    order_line_id  BIGINT      NOT NULL REFERENCES order_line(id),
    warehouse_id   BIGINT      NOT NULL,
    location_id    BIGINT      NOT NULL REFERENCES location(id),
    lpn_id         BIGINT      REFERENCES lpn(id),
    customer_id    BIGINT      NOT NULL,
    sku_id         BIGINT      NOT NULL,
    lot_id         BIGINT,
    inv_status     VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE',
    qty            INT         NOT NULL CHECK (qty > 0),
    qty_picked     INT         NOT NULL DEFAULT 0,
    status         VARCHAR(10) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','PICKED','SHIPPED','RELEASED')),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (qty_picked <= qty)
);
CREATE INDEX idx_alloc_line ON allocation(order_line_id);

-- ---------- 包裹 / 发运 ----------
CREATE TABLE shipment (
    id                  BIGSERIAL PRIMARY KEY,
    order_id            BIGINT      NOT NULL REFERENCES sales_order(id),
    carrier_code        VARCHAR(16) NOT NULL REFERENCES carrier(code),
    service_code        VARCHAR(32) NOT NULL,
    carrier_account_id  BIGINT      REFERENCES carrier_account(id),
    status              VARCHAR(12) NOT NULL DEFAULT 'PENDING'
                        CHECK (status IN ('PENDING','LABELED','FAILED','VOIDED','MANIFESTED')),
    total_cost          NUMERIC(10,2),
    currency            CHAR(3)     NOT NULL DEFAULT 'USD',
    error               TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    labeled_at          TIMESTAMPTZ
);
CREATE INDEX idx_shipment_order ON shipment(order_id);

CREATE TABLE package (
    id            BIGSERIAL PRIMARY KEY,
    order_id      BIGINT        NOT NULL REFERENCES sales_order(id),
    package_no    INT           NOT NULL,
    box_type_id   BIGINT        REFERENCES box_type(id),
    length_in     NUMERIC(10,2) NOT NULL,
    width_in      NUMERIC(10,2) NOT NULL,
    height_in     NUMERIC(10,2) NOT NULL,
    weight_lb     NUMERIC(10,3) NOT NULL CHECK (weight_lb > 0),
    shipment_id   BIGINT        REFERENCES shipment(id),
    tracking_no   VARCHAR(64),
    label_format  VARCHAR(8),                  -- ZPL / PDF
    label_data    TEXT,                        -- 面单内容（生产环境存 S3，此处存 key 或内容）
    cost          NUMERIC(10,2),
    UNIQUE (order_id, package_no)
);

CREATE TABLE package_item (
    package_id  BIGINT NOT NULL REFERENCES package(id),
    sku_id      BIGINT NOT NULL REFERENCES sku(id),
    qty         INT    NOT NULL CHECK (qty > 0),
    PRIMARY KEY (package_id, sku_id)
);

-- 每次比价保留全部报价，用于事后对账与节省分析
CREATE TABLE rate_quote (
    id             BIGSERIAL PRIMARY KEY,
    order_id       BIGINT        NOT NULL REFERENCES sales_order(id),
    carrier_code   VARCHAR(16)   NOT NULL,
    service_code   VARCHAR(32)   NOT NULL,
    amount         NUMERIC(10,2) NOT NULL,
    currency       CHAR(3)       NOT NULL DEFAULT 'USD',
    transit_days   INT,
    selected       BOOLEAN       NOT NULL DEFAULT FALSE,
    quoted_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_rate_quote_order ON rate_quote(order_id, quoted_at);

-- ---------- 计费事件（从第一天就记录，P3 月结时汇总成 Invoice） ----------
CREATE TABLE billable_event (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT        NOT NULL REFERENCES tenant(id),
    warehouse_id  BIGINT        NOT NULL REFERENCES warehouse(id),
    customer_id   BIGINT        NOT NULL REFERENCES customer(id),
    event_type    VARCHAR(24)   NOT NULL,   -- ORDER_FULFILLMENT / PICK_UNIT / PACKAGE / SHIPPING_LABEL / RECEIVING ...
    ref_type      VARCHAR(24)   NOT NULL,
    ref_id        BIGINT        NOT NULL,
    quantity      NUMERIC(12,3) NOT NULL DEFAULT 1,
    cost_amount   NUMERIC(12,2),            -- 实际成本（如承运商运费），计费引擎据此加价
    occurred_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    invoice_id    BIGINT,                   -- 开票后回填并锁定
    UNIQUE (event_type, ref_type, ref_id)   -- 同一业务事件只计一次
);
CREATE INDEX idx_billable_customer ON billable_event(customer_id, occurred_at) WHERE invoice_id IS NULL;

-- ---------- 单号序列 ----------
CREATE SEQUENCE order_no_seq START 100001;

-- ---------- 基础承运商数据（服务代码为系统内统一代码） ----------
INSERT INTO carrier(code, name) VALUES
    ('UPS', 'UPS'), ('FEDEX', 'FedEx'), ('USPS', 'USPS'), ('DHL_ECOM', 'DHL eCommerce');
UPDATE carrier SET active = FALSE WHERE code = 'DHL_ECOM';   -- 暂缓

INSERT INTO carrier_service(carrier_code, service_code, carrier_ref, name, transit_days_min, transit_days_max) VALUES
    ('UPS',   'UPS_GROUND',        '03', 'UPS Ground',               1, 5),
    ('UPS',   'UPS_2DAY',          '02', 'UPS 2nd Day Air',          2, 2),
    ('UPS',   'UPS_NEXT_DAY',      '01', 'UPS Next Day Air',         1, 1),
    ('FEDEX', 'FEDEX_GROUND',      'FEDEX_GROUND',      'FedEx Ground',      1, 5),
    ('FEDEX', 'FEDEX_HOME',        'GROUND_HOME_DELIVERY', 'FedEx Home Delivery', 1, 5),
    ('FEDEX', 'FEDEX_2DAY',        'FEDEX_2_DAY',       'FedEx 2Day',        2, 2),
    ('USPS',  'USPS_GROUND_ADV',   'USPS_GROUND_ADVANTAGE', 'USPS Ground Advantage', 2, 5),
    ('USPS',  'USPS_PRIORITY',     'PRIORITY_MAIL',     'USPS Priority Mail', 1, 3);
