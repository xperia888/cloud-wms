"""
回放 OutboundService / InventoryRepository 中的 SQL 常量，在加载了 dev 测试数据的 PostgreSQL 上
走完整条小包出库链路：建单 → 分配 → 拣货 → 打包 → 比价上下文 → 发货扣库存 → 计费事件，
以及取消释放、库存不足、并发分配。

用法：
  createdb wms_dev，依次执行 db/migration 与 db/dev 下的脚本，然后
  python3 tools/verify_outbound.py src/main/java/com/cloudwms "dbname=wms_dev user=wms password=wms host=localhost"
"""
import json
import re
import sys
import threading
from pathlib import Path

import psycopg2

SRC = Path(sys.argv[1])
DSN = sys.argv[2] if len(sys.argv) > 2 else "dbname=wms_dev user=wms password=wms host=localhost"


def load_constants(path):
    text = path.read_text()
    consts = {}
    for m in re.finditer(r'static final String (\w+)\s*=\s*(.*?);\n', text, re.S):
        name, expr = m.group(1), m.group(2)
        out = ""
        for tb, s, ident in re.findall(r'"""\n(.*?)"""|"((?:[^"\\]|\\.)*)"|(\b[A-Z_]{3,}\b)', expr, re.S):
            out += consts[ident] if ident else (tb or s)
        consts[name] = out
    return consts


C = {}
for f in ["inventory/InventoryRepository.java", "outbound/OutboundService.java"]:
    C.update(load_constants(SRC / f))


def lit(v):
    if v is None:
        return "NULL"
    if isinstance(v, bool):
        return "TRUE" if v else "FALSE"
    if isinstance(v, (list, tuple)):
        return ",".join(lit(x) for x in v)
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def run(cur, name, **params):
    cur.execute(re.sub(r'(?<!:):([a-zA-Z]\w*)', lambda m: lit(params[m.group(1)]), C[name]))
    if cur.description:
        cols = [d[0] for d in cur.description]
        return [dict(zip(cols, r)) for r in cur.fetchall()]
    return cur.rowcount


passed = 0


def check(cond, msg):
    global passed
    if not cond:
        print("FAIL:", msg)
        sys.exit(1)
    passed += 1
    print("ok  -", msg)


conn = psycopg2.connect(DSN)
cur = conn.cursor()
cur.execute("SELECT id FROM tenant WHERE code='SELF'"); TEN = cur.fetchone()[0]
cur.execute("SELECT id FROM warehouse WHERE code='LAX1'"); WH = cur.fetchone()[0]
cur.execute("SELECT id, code FROM customer"); CUST = {c: i for i, c in cur.fetchall()}
cur.execute("SELECT id, sku_code FROM sku"); SKU = {c: i for i, c in cur.fetchall()}


def available(sku):
    cur.execute("SELECT qty_available FROM v_inventory_available WHERE sku_id=%s", (SKU[sku],))
    r = cur.fetchone()
    return r[0] if r else 0


def lock_order(cur, oid, expected=None):
    o = run(cur, "SQL_LOCK_ORDER", orderId=oid, tenantId=TEN)[0]
    if expected:
        assert o["status"] == expected, (o["status"], expected)
    return o


def allocate(cur, oid):
    """same algorithm as OutboundService.allocate; returns shortfall (0 = success)"""
    o = lock_order(cur, oid, "NEW")
    for line in sorted(run(cur, "SQL_ORDER_LINES", orderId=oid), key=lambda l: (l["sku_id"], l["id"])):
        remaining = line["qty_ordered"] - line["qty_allocated"]
        for c in run(cur, "SQL_ALLOCATION_CANDIDATES", warehouseId=o["warehouse_id"],
                     customerId=o["customer_id"], skuId=line["sku_id"]):
            if remaining == 0:
                break
            take = min(remaining, c["available"])
            key = dict(warehouseId=o["warehouse_id"], locationId=c["location_id"], lpnId=c["lpn_id"],
                       customerId=o["customer_id"], skuId=line["sku_id"], lotId=c["lot_id"], status="AVAILABLE")
            if not run(cur, "SQL_ALLOCATE", qty=take, **key):
                continue
            run(cur, "SQL_INSERT_ALLOCATION", lineId=line["id"], qty=take, **key)
            run(cur, "SQL_ADD_LINE_ALLOCATED", lineId=line["id"], qty=take)
            remaining -= take
        if remaining:
            return remaining
    run(cur, "SQL_SET_ORDER_STATUS", orderId=oid, status="ALLOCATED")
    return 0


# ---------------------------------------------------------------- 1. create order (idempotent)
own = run(cur, "SQL_CHECK_OWNERSHIP", warehouseId=WH, customerId=CUST["ACME"])[0]
check(own["wh_tenant"] == TEN and own["cust_tenant"] == TEN, "仓库与货主归属校验")
sku_by_code = run(cur, "SQL_RESOLVE_SKU", customerId=CUST["ACME"], skuId=None, skuCode="ACME-TENT-2P")
check(len(sku_by_code) == 1, "按 skuCode 解析 SKU")
check(run(cur, "SQL_RESOLVE_SKU", customerId=CUST["BETA"], skuId=None, skuCode="ACME-TENT-2P") == [],
      "别的货主的 SKU 解析不到（货主隔离）")
ship_to = {"name": "Sam Ortiz", "address1": "1 Market St", "city": "San Francisco", "state": "CA",
           "zip": "94105", "country": "US", "residential": True}
params = dict(tenantId=TEN, warehouseId=WH, customerId=CUST["ACME"], source="API", externalOrderNo="API-5001",
              shipTo=json.dumps(ship_to), requestedService=None, reference=None)
new_id = run(cur, "SQL_INSERT_ORDER", **params)[0]["id"]
run(cur, "SQL_INSERT_LINE", orderId=new_id, lineNo=1, skuId=SKU["ACME-HEADLAMP"], qty=3)
dup = run(cur, "SQL_INSERT_ORDER", **params)
check(dup == [] and run(cur, "SQL_FIND_ORDER_BY_EXTERNAL", **params)[0]["id"] == new_id,
      "同一外部单号重复推送不建新单，返回原订单")
conn.commit()

# ---------------------------------------------------------------- 2. allocate seed order with FEFO
cur.execute("SELECT id FROM sales_order WHERE external_order_no='B2C-88001'"); beta_order = cur.fetchone()[0]
serum_before = available("BETA-SERUM-30ML")
check(allocate(cur, beta_order) == 0, "BETA 订单分配成功")
conn.commit()
cur.execute("""SELECT t.lot_no, l.code, a.qty FROM allocation a JOIN lot t ON t.id=a.lot_id
               JOIN location l ON l.id=a.location_id WHERE a.sku_id=%s""", (SKU["BETA-SERUM-30ML"],))
rows = cur.fetchall()
check(rows == [("S2026A", "P-01-05", 2)], f"先到期先出：精华液分配到较早批次 S2026A：{rows}")
check(available("BETA-SERUM-30ML") == serum_before - 2, "分配后可用量减少 2，在库量不变")
cur.execute("SELECT count(*) FROM allocation a JOIN location l ON l.id=a.location_id WHERE NOT l.pickable")
check(cur.fetchone()[0] == 0, "不会分配整托储位（不可拣库位）上的库存")

# ---------------------------------------------------------------- 3. pick + pack
lock_order(cur, beta_order, "ALLOCATED")
run(cur, "SQL_PICK_ALL_ALLOCATIONS", orderId=beta_order); run(cur, "SQL_PICK_ALL_LINES", orderId=beta_order)
run(cur, "SQL_SET_ORDER_STATUS", orderId=beta_order, status="PICKED")
box = run(cur, "SQL_BOX_TYPE", tenantId=TEN, code="BOX-S")[0]
pkg = run(cur, "SQL_INSERT_PACKAGE", orderId=beta_order, packageNo=1, boxTypeId=box["id"],
          lengthIn=box["length_in"], widthIn=box["width_in"], heightIn=box["height_in"], weightLb=1.4)[0]["id"]
run(cur, "SQL_INSERT_PACKAGE_ITEM", packageId=pkg, skuId=SKU["BETA-SERUM-30ML"], qty=2)
run(cur, "SQL_INSERT_PACKAGE_ITEM", packageId=pkg, skuId=SKU["BETA-CREAM-50"], qty=1)
run(cur, "SQL_SET_ORDER_STATUS", orderId=beta_order, status="PACKED")
conn.commit()
check(lock_order(cur, beta_order)["status"] == "PACKED", "拣货、打包后订单为 PACKED"); conn.commit()

# ---------------------------------------------------------------- 4. rate shopping context
accts = run(cur, "SQL_CARRIER_ACCOUNTS", tenantId=TEN, customerId=CUST["BETA"])
check(sorted(a["carrier_code"] for a in accts) == ["FEDEX", "UPS", "USPS"], "可用承运商账号：UPS/FedEx/USPS（DHL 暂缓不出现）")
rule = run(cur, "SQL_SHIP_RULE", customerId=CUST["BETA"])[0]
check(rule["strategy"] == "CHEAPEST" and rule["max_transit_days"] == 3, "读取 BETA 发运规则：最低价、3 天内")
active = {r["svc"] for r in run(cur, "SQL_ACTIVE_SERVICES")}
check("UPS:UPS_GROUND" in active and len(active) == 8, f"启用的服务 {len(active)} 个")
wh = run(cur, "SQL_WAREHOUSE_ADDRESS", warehouseId=WH)[0]
check(json.loads(wh["address"])["zip"] == "91761", "仓库发货地址可解析")
pk = run(cur, "SQL_PACKAGES", orderId=beta_order)
check(len(pk) == 1 and float(pk[0]["weight_lb"]) == 1.4, "包裹尺寸重量读取正确")
run(cur, "SQL_INSERT_QUOTE", orderId=beta_order, carrierCode="USPS", serviceCode="USPS_GROUND_ADV", amount=7.15,
    currency="USD", transitDays=3, selected=True)

# ---------------------------------------------------------------- 5. ship (step 1 + 3)
o = lock_order(cur, beta_order, "PACKED")
check(run(cur, "SQL_ACTIVE_SHIPMENTS", orderId=beta_order)[0]["count"] == 0, "无进行中的发运")
acct = next(a for a in accts if a["carrier_code"] == "USPS")
shp = run(cur, "SQL_INSERT_SHIPMENT", orderId=beta_order, carrierCode="USPS", serviceCode="USPS_GROUND_ADV",
          accountId=acct["id"])[0]["id"]
conn.commit()
o2 = psycopg2.connect(DSN).cursor()
o2.execute(re.sub(r'(?<!:):([a-zA-Z]\w*)', lambda m: lit({"orderId": beta_order}[m.group(1)]), C["SQL_ACTIVE_SHIPMENTS"]))
check(o2.fetchone()[0] == 1, "第二次发货请求会看到进行中的发运而被拒绝（防重复打单）")

lock_order(cur, beta_order, "PACKED")
run(cur, "SQL_PACKAGE_LABEL", packageId=pkg, shipmentId=shp, trackingNo="9400000000000000000001",
    labelFormat="ZPL", labelData="^XA^FDTEST^FS^XZ", cost=7.15)
run(cur, "SQL_SHIPMENT_LABELED", shipmentId=shp, totalCost=7.15, currency="USD")
on_hand_before = {}
cur.execute("SELECT sku_id, sum(qty_on_hand) FROM inventory_balance GROUP BY sku_id")
on_hand_before = dict(cur.fetchall())
cur.execute("INSERT INTO inventory_txn_group(tenant_id, group_type, created_by) VALUES (%s,'SHIP','t') RETURNING id", (TEN,))
gid = cur.fetchone()[0]
units = 0
for a in run(cur, "SQL_PICKED_ALLOCATIONS", orderId=beta_order, statuses=["PICKED"]):
    key = dict(warehouseId=a["warehouse_id"], locationId=a["location_id"], lpnId=a["lpn_id"],
               customerId=a["customer_id"], skuId=a["sku_id"], lotId=a["lot_id"], status="AVAILABLE")
    check(run(cur, "SQL_DEDUCT_ALLOCATED", qty=a["qty_picked"], **key) != [], f"按分配扣减 sku {a['sku_id']} × {a['qty_picked']}")
    run(cur, "SQL_INSERT_TXN", groupId=gid, tenantId=TEN, txnType="SHIP", qtyDelta=-a["qty_picked"], **key)
    run(cur, "SQL_SET_ALLOCATION_STATUS", allocationId=a["id"], status="SHIPPED")
    units += a["qty_picked"]
run(cur, "SQL_LINES_SHIPPED", orderId=beta_order)
run(cur, "SQL_ORDER_SHIPPED", orderId=beta_order)
b = dict(tenantId=TEN, warehouseId=WH, customerId=CUST["BETA"])
run(cur, "SQL_INSERT_BILLABLE", eventType="ORDER_FULFILLMENT", refType="ORDER", refId=beta_order, quantity=1, costAmount=None, **b)
run(cur, "SQL_INSERT_BILLABLE", eventType="PICK_UNIT", refType="ORDER", refId=beta_order, quantity=units, costAmount=None, **b)
run(cur, "SQL_INSERT_BILLABLE", eventType="PACKAGE", refType="ORDER", refId=beta_order, quantity=1, costAmount=None, **b)
run(cur, "SQL_INSERT_BILLABLE", eventType="SHIPPING_LABEL", refType="SHIPMENT", refId=shp, quantity=1, costAmount=7.15, **b)
dup = run(cur, "SQL_INSERT_BILLABLE", eventType="SHIPPING_LABEL", refType="SHIPMENT", refId=shp, quantity=1, costAmount=7.15, **b)
conn.commit()
check(dup == 0, "同一计费事件重复写入被忽略")
cur.execute("SELECT event_type, quantity, cost_amount FROM billable_event WHERE customer_id=%s ORDER BY id", (CUST["BETA"],))
ev = cur.fetchall()
check([e[0] for e in ev] == ["ORDER_FULFILLMENT", "PICK_UNIT", "PACKAGE", "SHIPPING_LABEL"] and float(ev[1][1]) == 3,
      f"计费事件：订单 1、拣货 {units} 件、包裹 1、运费 7.15")
cur.execute("SELECT sku_id, sum(qty_on_hand) FROM inventory_balance GROUP BY sku_id")
after = dict(cur.fetchall())
check(after[SKU["BETA-SERUM-30ML"]] == on_hand_before[SKU["BETA-SERUM-30ML"]] - 2 and
      after[SKU["BETA-CREAM-50"]] == on_hand_before[SKU["BETA-CREAM-50"]] - 1, "发货后在库量扣减正确")
cur.execute("SELECT count(*) FROM v_inventory_reconcile")
check(cur.fetchone()[0] == 0, "发货后库存对账一致")

# ---------------------------------------------------------------- 6. views
v = run(cur, "SQL_ORDER_VIEW", orderId=beta_order, tenantId=TEN)[0]
check(v["status"] == "SHIPPED" and v["shipped_at"] is not None, "订单 SHIPPED 且有发货时间")
lines = run(cur, "SQL_LINE_VIEW", orderId=beta_order)
check(all(l["qty_shipped"] == l["qty_ordered"] for l in lines), "订单行已发数量 = 订购数量")
check(len(run(cur, "SQL_PACKAGE_ITEMS", orderId=beta_order)) == 2, "包裹明细 2 个 SKU")
check(run(cur, "SQL_LABEL", packageId=pkg, tenantId=TEN)[0]["label_format"] == "ZPL", "可取回 ZPL 面单")
check(run(cur, "SQL_LABEL", packageId=pkg, tenantId=TEN + 999) == [], "其他租户取不到面单（租户隔离）")
conn.commit()

# ---------------------------------------------------------------- 7. cancel releases allocation
head_before = available("ACME-HEADLAMP")
check(allocate(cur, new_id) == 0, "新订单分配 3 个头灯"); conn.commit()
check(available("ACME-HEADLAMP") == head_before - 3, "分配后可用 -3")
for a in run(cur, "SQL_PICKED_ALLOCATIONS", orderId=new_id, statuses=["OPEN", "PICKED"]):
    key = dict(warehouseId=a["warehouse_id"], locationId=a["location_id"], lpnId=a["lpn_id"],
               customerId=a["customer_id"], skuId=a["sku_id"], lotId=a["lot_id"], status="AVAILABLE")
    check(run(cur, "SQL_DEALLOCATE", qty=a["qty"], **key) != [], "释放分配")
    run(cur, "SQL_SET_ALLOCATION_STATUS", allocationId=a["id"], status="RELEASED")
run(cur, "SQL_LINES_RELEASED", orderId=new_id)
run(cur, "SQL_SET_ORDER_STATUS", orderId=new_id, status="CANCELLED")
conn.commit()
check(available("ACME-HEADLAMP") == head_before, "取消订单后可用量恢复")

# ---------------------------------------------------------------- 8. insufficient pickable stock
cur.execute("SELECT id FROM sales_order WHERE external_order_no='SHOP-1003'"); chair_order = cur.fetchone()[0]
cur.execute("UPDATE order_line SET qty_ordered = 10 WHERE order_id=%s", (chair_order,))
short = allocate(cur, chair_order)
conn.rollback()
check(short == 4, "拣货位只有 6 把椅子、订单要 10：缺 4，整单回滚（整托储位需补货）")
check(available("ACME-CHAIR") == 46, "回滚后椅子可用量不变")

# ---------------------------------------------------------------- 9. concurrent allocation, two orders, limited stock
def mk_order(ext, qty):
    p = dict(params, externalOrderNo=ext)
    oid = run(cur, "SQL_INSERT_ORDER", **p)[0]["id"]
    run(cur, "SQL_INSERT_LINE", orderId=oid, lineNo=1, skuId=SKU["ACME-CHAIR"], qty=qty)
    return oid


o_a, o_b = mk_order("RACE-A", 4), mk_order("RACE-B", 4)
conn.commit()
res = {}
barrier = threading.Barrier(2)


def race(name, oid):
    c = psycopg2.connect(DSN); cu = c.cursor()
    barrier.wait()
    short = allocate(cu, oid)
    if short:
        c.rollback()
    else:
        c.commit()
    res[name] = short
    c.close()


ts = [threading.Thread(target=race, args=("A", o_a)), threading.Thread(target=race, args=("B", o_b))]
[t.start() for t in ts]; [t.join() for t in ts]
check(sorted(res.values()) == [0, 2], f"两单并发抢 6 把椅子（各要 4）：一单成功，另一单缺 2 回滚 {res}")
cur.execute("""SELECT sum(qty_allocated) FROM inventory_balance b JOIN location l ON l.id=b.location_id
               WHERE b.sku_id=%s AND l.pickable""", (SKU["ACME-CHAIR"],))
check(cur.fetchone()[0] == 4, "拣货位上已分配 4，未超分配")
cur.execute("SELECT count(*) FROM v_inventory_reconcile")
check(cur.fetchone()[0] == 0, "最终库存对账一致")

print(f"\nALL {passed} OUTBOUND CHECKS PASSED")
