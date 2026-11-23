-- =====================================================================
-- V1 主数据：租户、仓库、库区、库位、货主、SKU、批次
-- 所有业务表都带 tenant_id：一期自用只有一个租户，日后 SaaS 化无需重构
-- =====================================================================

CREATE TABLE tenant (
    id          BIGSERIAL PRIMARY KEY,
    code        VARCHAR(32)  NOT NULL UNIQUE,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE warehouse (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenant(id),
    code        VARCHAR(16)  NOT NULL,              -- 例：LAX1，也用于 LPN 编号
    name        VARCHAR(200) NOT NULL,
    timezone    VARCHAR(64)  NOT NULL DEFAULT 'America/Los_Angeles',
    address     JSONB,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

CREATE TABLE zone (
    id            BIGSERIAL PRIMARY KEY,
    warehouse_id  BIGINT      NOT NULL REFERENCES warehouse(id),
    code          VARCHAR(32) NOT NULL,
    zone_type     VARCHAR(16) NOT NULL DEFAULT 'STORAGE'
                  CHECK (zone_type IN ('STORAGE','PICK','RECEIVING','STAGING','SHIPPING','RETURNS','QC')),
    UNIQUE (warehouse_id, code)
);

CREATE TABLE location (
    id            BIGSERIAL PRIMARY KEY,
    warehouse_id  BIGINT      NOT NULL REFERENCES warehouse(id),
    zone_id       BIGINT      REFERENCES zone(id),
    code          VARCHAR(32) NOT NULL,              -- 例：A-01-02-03
    location_type VARCHAR(16) NOT NULL DEFAULT 'RACK'
                  CHECK (location_type IN ('RACK','SHELF','FLOOR','DOCK','STAGING','VIRTUAL')),
    pickable      BOOLEAN     NOT NULL DEFAULT TRUE,
    max_pallets   INT,
    status        VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE','BLOCKED','INACTIVE')),
    UNIQUE (warehouse_id, code)
);

-- 货主（Customer）：可跨仓存放
CREATE TABLE customer (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT       NOT NULL REFERENCES tenant(id),
    code        VARCHAR(32)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, code)
);

CREATE TABLE sku (
    id                 BIGSERIAL PRIMARY KEY,
    tenant_id          BIGINT       NOT NULL REFERENCES tenant(id),
    customer_id        BIGINT       NOT NULL REFERENCES customer(id),
    sku_code           VARCHAR(64)  NOT NULL,
    name               VARCHAR(300) NOT NULL,
    base_uom           VARCHAR(16)  NOT NULL DEFAULT 'EA',
    length_in          NUMERIC(10,2),
    width_in           NUMERIC(10,2),
    height_in          NUMERIC(10,2),
    weight_lb          NUMERIC(10,3),
    lot_controlled     BOOLEAN NOT NULL DEFAULT FALSE,
    expiry_controlled  BOOLEAN NOT NULL DEFAULT FALSE,
    serial_controlled  BOOLEAN NOT NULL DEFAULT FALSE,
    hazmat             BOOLEAN NOT NULL DEFAULT FALSE,
    hs_code            VARCHAR(16),
    active             BOOLEAN NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (customer_id, sku_code)
);

-- 多单位换算：1 CS = 12 EA，1 PL = 60 CS ...
CREATE TABLE sku_uom (
    sku_id       BIGINT      NOT NULL REFERENCES sku(id),
    uom          VARCHAR(16) NOT NULL,
    qty_per_uom  INT         NOT NULL CHECK (qty_per_uom > 0),  -- 折合多少个 base_uom
    PRIMARY KEY (sku_id, uom)
);

CREATE TABLE sku_barcode (
    sku_id   BIGINT      NOT NULL REFERENCES sku(id),
    barcode  VARCHAR(64) NOT NULL,
    uom      VARCHAR(16) NOT NULL DEFAULT 'EA',
    PRIMARY KEY (sku_id, barcode)
);
CREATE INDEX idx_sku_barcode_code ON sku_barcode(barcode);

CREATE TABLE lot (
    id           BIGSERIAL PRIMARY KEY,
    sku_id       BIGINT      NOT NULL REFERENCES sku(id),
    lot_no       VARCHAR(64) NOT NULL,
    expiry_date  DATE,
    mfg_date     DATE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (sku_id, lot_no)
);
