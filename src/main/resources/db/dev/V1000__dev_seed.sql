-- =====================================================================
-- 开发 / 测试数据（仅在 dev 配置下加载：spring.profiles.active=dev）
-- 生产环境不会执行本目录下的脚本。
--
-- 内容：1 个租户、试点仓 LAX1、库区与库位、2 个货主、7 个 SKU（含批次管理）、
--       LPN 规则与托盘、期初库存（通过流水写入，对账一致）、箱型、承运商账号、发运规则、5 张示例出库单
-- =====================================================================

INSERT INTO tenant(code, name) VALUES ('SELF', 'Self-operated');

INSERT INTO warehouse(tenant_id, code, name, address)
SELECT id, 'LAX1', 'LA Pilot Warehouse',
       '{"address1":"1000 E Example Ave","city":"Ontario","state":"CA","zip":"91761","country":"US"}'
FROM tenant WHERE code = 'SELF';

INSERT INTO zone(warehouse_id, code, zone_type)
SELECT w.id, z.code, z.zone_type
FROM warehouse w,
     (VALUES ('RCV','RECEIVING'), ('RSV','STORAGE'), ('PCK','PICK'), ('PKS','STAGING'), ('SHP','SHIPPING')) AS z(code, zone_type)
WHERE w.code = 'LAX1';

-- 库位：收货门、整托储位（不可直接拣）、拣货货架、打包台、发货门
INSERT INTO location(warehouse_id, zone_id, code, location_type, pickable)
SELECT w.id, z.id, l.code, l.ltype, l.pickable
FROM warehouse w
JOIN (VALUES
    ('RCV', 'DOCK-IN-01',  'DOCK',    FALSE),
    ('RSV', 'A-01-01',     'RACK',    FALSE),
    ('RSV', 'A-01-02',     'RACK',    FALSE),
    ('RSV', 'A-01-03',     'RACK',    FALSE),
    ('RSV', 'A-02-01',     'RACK',    FALSE),
    ('RSV', 'A-02-02',     'RACK',    FALSE),
    ('RSV', 'A-02-03',     'RACK',    FALSE),
    ('PCK', 'P-01-01',     'SHELF',   TRUE),
    ('PCK', 'P-01-02',     'SHELF',   TRUE),
    ('PCK', 'P-01-03',     'SHELF',   TRUE),
    ('PCK', 'P-01-04',     'SHELF',   TRUE),
    ('PCK', 'P-01-05',     'SHELF',   TRUE),
    ('PCK', 'P-01-06',     'SHELF',   TRUE),
    ('PCK', 'P-01-07',     'SHELF',   TRUE),
    ('PKS', 'PACK-01',     'STAGING', FALSE),
    ('SHP', 'DOCK-OUT-01', 'DOCK',    FALSE)
) AS l(zone_code, code, ltype, pickable) ON TRUE
JOIN zone z ON z.warehouse_id = w.id AND z.code = l.zone_code
WHERE w.code = 'LAX1';

INSERT INTO customer(tenant_id, code, name)
SELECT t.id, c.code, c.name FROM tenant t,
       (VALUES ('ACME', 'Acme Outdoor Co.'), ('BETA', 'Beta Beauty LLC')) AS c(code, name)
WHERE t.code = 'SELF';

INSERT INTO sku(tenant_id, customer_id, sku_code, name, length_in, width_in, height_in, weight_lb,
                lot_controlled, expiry_controlled)
SELECT c.tenant_id, c.id, s.code, s.name, s.l, s.w, s.h, s.lb, s.lot, s.exp
FROM customer c
JOIN (VALUES
    ('ACME', 'ACME-TENT-2P',    '2-Person Backpacking Tent',  18.0, 8.0, 8.0, 5.200, FALSE, FALSE),
    ('ACME', 'ACME-BOTTLE-32',  'Insulated Bottle 32oz',       4.0, 4.0, 11.0, 1.100, FALSE, FALSE),
    ('ACME', 'ACME-HEADLAMP',   'LED Headlamp',                6.0, 4.0, 3.0, 0.400, FALSE, FALSE),
    ('ACME', 'ACME-CHAIR',      'Folding Camp Chair',         36.0, 7.0, 7.0, 8.500, FALSE, FALSE),
    ('BETA', 'BETA-SERUM-30ML', 'Vitamin C Serum 30ml',        2.0, 2.0, 5.0, 0.300, TRUE,  TRUE),
    ('BETA', 'BETA-CREAM-50',   'Night Cream 50ml',            3.0, 3.0, 3.0, 0.500, TRUE,  TRUE),
    ('BETA', 'BETA-GIFTSET',    'Holiday Gift Set',           10.0, 8.0, 4.0, 2.200, FALSE, FALSE)
) AS s(cust, code, name, l, w, h, lb, lot, exp) ON s.cust = c.code;

INSERT INTO sku_barcode(sku_id, barcode)
SELECT s.id, b.barcode FROM sku s
JOIN (VALUES ('ACME-TENT-2P','810000000011'), ('ACME-BOTTLE-32','810000000028'),
             ('ACME-HEADLAMP','810000000035'), ('ACME-CHAIR','810000000042'),
             ('BETA-SERUM-30ML','820000000010'), ('BETA-CREAM-50','820000000027'),
             ('BETA-GIFTSET','820000000034')) AS b(code, barcode) ON b.code = s.sku_code;

INSERT INTO sku_uom(sku_id, uom, qty_per_uom)
SELECT s.id, u.uom, u.qty FROM sku s
JOIN (VALUES ('ACME-BOTTLE-32','CS',12), ('ACME-HEADLAMP','CS',24), ('BETA-SERUM-30ML','CS',48),
             ('BETA-CREAM-50','CS',36)) AS u(code, uom, qty) ON u.code = s.sku_code;

INSERT INTO lot(sku_id, lot_no, expiry_date)
SELECT s.id, l.lot_no, l.exp::date FROM sku s
JOIN (VALUES ('BETA-SERUM-30ML','S2026A','2027-06-30'), ('BETA-SERUM-30ML','S2026B','2027-12-31'),
             ('BETA-CREAM-50','C2026A','2027-09-30')) AS l(code, lot_no, exp) ON l.code = s.sku_code;

-- LPN 规则：LP + LAX1 + 8 位流水号，新号从 1001 开始（为旧系统托盘号留出空间）
INSERT INTO lpn_rule(warehouse_id, prefix, seq_padding, next_seq)
SELECT id, 'LP', 8, 1001 FROM warehouse WHERE code = 'LAX1';

-- 托盘：前 4 个为本系统规则号，最后 1 个模拟旧系统迁移过来的托盘号
INSERT INTO lpn(tenant_id, warehouse_id, lpn_no, lpn_type, location_id, customer_id, source)
SELECT w.tenant_id, w.id, p.lpn_no, 'PALLET', l.id, c.id, p.src
FROM warehouse w
JOIN (VALUES ('LPLAX100000001','A-01-01','ACME','GENERATED'),
             ('LPLAX100000002','A-01-02','ACME','GENERATED'),
             ('LPLAX100000003','A-01-03','ACME','GENERATED'),
             ('LPLAX100000004','A-02-01','BETA','GENERATED'),
             ('OLD-000451',    'A-02-02','BETA','MIGRATED')) AS p(lpn_no, loc, cust, src) ON TRUE
JOIN location l ON l.warehouse_id = w.id AND l.code = p.loc
JOIN customer c ON c.tenant_id = w.tenant_id AND c.code = p.cust
WHERE w.code = 'LAX1';

-- 期初库存：同时写余额与流水，保证 v_inventory_reconcile 为空
CREATE TEMP TABLE seed_stock AS
SELECT w.tenant_id, w.id AS warehouse_id, l.id AS location_id, p.id AS lpn_id, c.id AS customer_id,
       s.id AS sku_id, t.id AS lot_id, v.qty
FROM (VALUES
    -- 库位       托盘号             货主    SKU                批次      数量
    ('A-01-01', 'LPLAX100000001', 'ACME', 'ACME-TENT-2P',    NULL,     60),
    ('A-01-02', 'LPLAX100000002', 'ACME', 'ACME-BOTTLE-32',  NULL,    480),
    ('A-01-03', 'LPLAX100000003', 'ACME', 'ACME-CHAIR',      NULL,     40),
    ('A-02-01', 'LPLAX100000004', 'BETA', 'BETA-SERUM-30ML', 'S2026B', 960),
    ('A-02-02', 'OLD-000451',     'BETA', 'BETA-GIFTSET',    NULL,    120),
    ('P-01-01', NULL,             'ACME', 'ACME-TENT-2P',    NULL,     12),
    ('P-01-02', NULL,             'ACME', 'ACME-BOTTLE-32',  NULL,     48),
    ('P-01-03', NULL,             'ACME', 'ACME-HEADLAMP',   NULL,    150),
    ('P-01-04', NULL,             'ACME', 'ACME-CHAIR',      NULL,      6),
    ('P-01-05', NULL,             'BETA', 'BETA-SERUM-30ML', 'S2026A', 40),
    ('P-01-05', NULL,             'BETA', 'BETA-SERUM-30ML', 'S2026B', 96),
    ('P-01-06', NULL,             'BETA', 'BETA-CREAM-50',   'C2026A', 72),
    ('P-01-07', NULL,             'BETA', 'BETA-GIFTSET',    NULL,     20)
) AS v(loc, lpn_no, cust, sku_code, lot_no, qty)
JOIN warehouse w ON w.code = 'LAX1'
JOIN location l  ON l.warehouse_id = w.id AND l.code = v.loc
JOIN customer c  ON c.tenant_id = w.tenant_id AND c.code = v.cust
JOIN sku s       ON s.customer_id = c.id AND s.sku_code = v.sku_code
LEFT JOIN lpn p  ON p.tenant_id = w.tenant_id AND p.lpn_no = v.lpn_no
LEFT JOIN lot t  ON t.sku_id = s.id AND t.lot_no = v.lot_no;

INSERT INTO inventory_balance(tenant_id, warehouse_id, location_id, lpn_id, customer_id, sku_id, lot_id, qty_on_hand)
SELECT tenant_id, warehouse_id, location_id, lpn_id, customer_id, sku_id, lot_id, qty FROM seed_stock;

WITH g AS (
    INSERT INTO inventory_txn_group(tenant_id, group_type, idempotency_key, ref_type, reason, created_by)
    SELECT id, 'MIGRATION', 'DEV-SEED-OPENING', 'SEED', 'dev seed opening stock', 'seed'
    FROM tenant WHERE code = 'SELF'
    RETURNING id
)
INSERT INTO inventory_txn(group_id, tenant_id, warehouse_id, txn_type, location_id, lpn_id, customer_id,
                          sku_id, lot_id, inv_status, qty_delta)
SELECT g.id, s.tenant_id, s.warehouse_id, 'MIGRATION', s.location_id, s.lpn_id, s.customer_id,
       s.sku_id, s.lot_id, 'AVAILABLE', s.qty
FROM seed_stock s, g;

DROP TABLE seed_stock;

-- 箱型
INSERT INTO box_type(tenant_id, code, length_in, width_in, height_in, tare_lb)
SELECT t.id, b.code, b.l, b.w, b.h, b.tare FROM tenant t,
       (VALUES ('BOX-S', 8, 6, 4, 0.30), ('BOX-M', 12, 10, 6, 0.60), ('BOX-L', 18, 14, 10, 1.20),
               ('BOX-XL', 38, 10, 10, 2.00), ('POLY-M', 12, 15, 1, 0.05)) AS b(code, l, w, h, tare)
WHERE t.code = 'SELF';

-- 承运商账号（沙箱；凭证在密钥系统中，这里只是条目名）
INSERT INTO carrier_account(tenant_id, carrier_code, account_no, credentials_ref, sandbox)
SELECT t.id, a.carrier, a.acct, a.ref, TRUE FROM tenant t,
       (VALUES ('UPS', 'SANDBOX-UPS', 'wms/carriers/ups/sandbox'),
               ('FEDEX', 'SANDBOX-FEDEX', 'wms/carriers/fedex/sandbox'),
               ('USPS', 'SANDBOX-USPS', 'wms/carriers/usps/sandbox')) AS a(carrier, acct, ref)
WHERE t.code = 'SELF';

-- 发运规则：ACME 最低价，运费加价 8%；BETA 最低价但限 3 天内送达
INSERT INTO ship_rule(tenant_id, customer_id, strategy, max_transit_days, markup_pct)
SELECT c.tenant_id, c.id, r.strategy, r.max_days, r.markup FROM customer c
JOIN (VALUES ('ACME', 'CHEAPEST', NULL::int, 8.00), ('BETA', 'CHEAPEST', 3, 5.00)) AS r(cust, strategy, max_days, markup)
  ON r.cust = c.code;

-- 示例出库单（状态 NEW）
CREATE TEMP TABLE seed_orders(ext_no TEXT, cust TEXT, source TEXT, ship_to JSONB, requested TEXT, lines JSONB);
INSERT INTO seed_orders VALUES
 ('SHOP-1001', 'ACME', 'SHOPIFY',
  '{"name":"Jamie Lee","address1":"742 Evergreen Ter","city":"Springfield","state":"OR","zip":"97477","country":"US","phone":"5415550100","residential":true}',
  NULL, '[{"sku":"ACME-TENT-2P","qty":1},{"sku":"ACME-HEADLAMP","qty":2}]'),
 ('SHOP-1002', 'ACME', 'SHOPIFY',
  '{"name":"Chris Park","address1":"88 Lake Shore Dr","city":"Chicago","state":"IL","zip":"60611","country":"US","phone":"3125550111","residential":true}',
  NULL, '[{"sku":"ACME-BOTTLE-32","qty":3}]'),
 ('SHOP-1003', 'ACME', 'SHOPIFY',
  '{"name":"Taylor Brooks","company":"Trailhead Outfitters","address1":"500 Main St","city":"Boulder","state":"CO","zip":"80302","country":"US","phone":"3035550123","residential":false}',
  'UPS:UPS_GROUND', '[{"sku":"ACME-CHAIR","qty":2}]'),
 ('B2C-88001', 'BETA', 'API',
  '{"name":"Morgan Diaz","address1":"12 Ocean Ave","city":"Miami","state":"FL","zip":"33139","country":"US","phone":"3055550188","residential":true}',
  NULL, '[{"sku":"BETA-SERUM-30ML","qty":2},{"sku":"BETA-CREAM-50","qty":1}]'),
 ('B2C-88002', 'BETA', 'API',
  '{"name":"Riley Chen","address1":"2100 Pine St","city":"Seattle","state":"WA","zip":"98101","country":"US","phone":"2065550199","residential":true}',
  NULL, '[{"sku":"BETA-GIFTSET","qty":1}]');

INSERT INTO sales_order(tenant_id, warehouse_id, customer_id, order_no, source, external_order_no, ship_to,
                        requested_service)
SELECT w.tenant_id, w.id, c.id, 'SO' || nextval('order_no_seq'), o.source, o.ext_no, o.ship_to, o.requested
FROM seed_orders o
JOIN warehouse w ON w.code = 'LAX1'
JOIN customer c ON c.tenant_id = w.tenant_id AND c.code = o.cust
ORDER BY o.ext_no;

INSERT INTO order_line(order_id, line_no, sku_id, qty_ordered)
SELECT so.id, x.ord, s.id, (x.line->>'qty')::int
FROM seed_orders o
JOIN sales_order so ON so.external_order_no = o.ext_no
CROSS JOIN LATERAL jsonb_array_elements(o.lines) WITH ORDINALITY AS x(line, ord)
JOIN sku s ON s.customer_id = so.customer_id AND s.sku_code = x.line->>'sku';

DROP TABLE seed_orders;
