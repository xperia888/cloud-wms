"""
Replays the exact SQL constants from the Java sources against a real PostgreSQL 16,
following the same call order as InventoryService, to verify the inventory engine logic
(the Java build itself cannot run here because Maven Central is unreachable).
"""
import re
import sys
import threading
import time
from pathlib import Path

import psycopg2

SRC = Path(sys.argv[1])
DSN = "dbname=wms user=wms password=wms host=localhost"


def load_constants(path):
    text = path.read_text()
    consts = {}
    # NAME = <expr>;  where expr = textblock / "str" / IDENT joined by +
    for m in re.finditer(r'static final String (\w+)\s*=\s*(.*?);\n', text, re.S):
        name, expr = m.group(1), m.group(2)
        parts = re.findall(r'"""\n(.*?)"""|"((?:[^"\\]|\\.)*)"|(\b[A-Z_]{3,}\b)', expr, re.S)
        out = ""
        for tb, s, ident in parts:
            if ident:
                out += consts[ident]
            elif tb or s:
                out += tb or s
        consts[name] = out
    return consts


C = {}
for f in ["inventory/InventoryRepository.java", "inventory/LpnService.java", "inventory/InventoryService.java"]:
    C.update(load_constants(SRC / f))


def lit(v):
    if v is None:
        return "NULL"
    if isinstance(v, (list, tuple)):
        return ",".join(lit(x) for x in v)
    if isinstance(v, (int, float)):
        return str(v)
    return "'" + str(v).replace("'", "''") + "'"


def bind(sql, params):
    # replace :name but not ::type casts
    return re.sub(r'(?<!:):([a-zA-Z]\w*)', lambda m: lit(params[m.group(1)]), sql)


def run(cur, name, **params):
    cur.execute(bind(C[name], params))
    return cur.fetchall() if cur.description else cur.rowcount


def key(loc, lpn=None, status="AVAILABLE"):
    return dict(warehouseId=WH, locationId=loc, lpnId=lpn, customerId=CUST, skuId=SKU, lotId=None, status=status)


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
cur.execute("""TRUNCATE inventory_balance, lpn, lpn_rule, lot, sku_barcode, sku_uom, sku, customer,
               location, zone, warehouse, tenant RESTART IDENTITY CASCADE""")
cur.execute("ALTER TABLE inventory_txn DISABLE TRIGGER trg_txn_append_only")
cur.execute("ALTER TABLE inventory_txn_group DISABLE TRIGGER trg_txn_group_append_only")
cur.execute("TRUNCATE inventory_txn, inventory_txn_group RESTART IDENTITY")
cur.execute("ALTER TABLE inventory_txn ENABLE TRIGGER trg_txn_append_only")
cur.execute("ALTER TABLE inventory_txn_group ENABLE TRIGGER trg_txn_group_append_only")
cur.execute("INSERT INTO tenant(code,name) VALUES ('SELF','Self') RETURNING id"); TEN = cur.fetchone()[0]
cur.execute("INSERT INTO warehouse(tenant_id,code,name) VALUES (%s,'LAX1','Pilot') RETURNING id", (TEN,)); WH = cur.fetchone()[0]
cur.execute("INSERT INTO location(warehouse_id,code) VALUES (%s,'A-01-01') RETURNING id", (WH,)); LA = cur.fetchone()[0]
cur.execute("INSERT INTO location(warehouse_id,code) VALUES (%s,'B-02-01') RETURNING id", (WH,)); LB = cur.fetchone()[0]
cur.execute("INSERT INTO customer(tenant_id,code,name) VALUES (%s,'ACME','Acme') RETURNING id", (TEN,)); CUST = cur.fetchone()[0]
cur.execute("INSERT INTO sku(tenant_id,customer_id,sku_code,name) VALUES (%s,%s,'ACME-001','Widget') RETURNING id", (TEN, CUST)); SKU = cur.fetchone()[0]
cur.execute("INSERT INTO lpn_rule(warehouse_id) VALUES (%s)", (WH,))
conn.commit()

ctx = dict(tenantId=TEN, operator="tester", refType="TEST", refId=None, reason=None)


def group(cur, gtype, idem=None):
    rows = run(cur, "SQL_INSERT_GROUP", groupType=gtype, idempotencyKey=idem, **ctx)
    return rows[0][0] if rows else None


def txn(cur, g, ttype, k, delta):
    run(cur, "SQL_INSERT_TXN", groupId=g, tenantId=TEN, txnType=ttype, qtyDelta=delta, **k)


def receive(cur, k, qty, idem=None):
    run(cur, "SQL_VALIDATE_KEY", **k)
    g = group(cur, "RECEIVE", idem)
    if g is None:
        return None
    run(cur, "SQL_ADD_ON_HAND", tenantId=TEN, qty=qty, **k)
    txn(cur, g, "RECEIVE", k, qty)
    return g


def deduct(cur, k, qty):
    rows = run(cur, "SQL_DEDUCT_AVAILABLE", qty=qty, **k)
    if rows and rows[0][1] == 0 and rows[0][2] == 0:
        run(cur, "SQL_DELETE_EMPTY", id=rows[0][0])
    return bool(rows)


def on_hand(loc, lpn=None):
    c = conn.cursor()
    rows = run(c, "SQL_QUERY_BALANCES", tenantId=TEN, warehouseId=WH, customerId=CUST, skuId=SKU,
               locationId=loc, lpnId=lpn, limit=100)
    return sum(r[9] for r in rows if r[4] == lpn)  # qty_on_hand column index 9 (b.*)


def reconcile_empty():
    c = conn.cursor()
    return run(c, "SQL_RECONCILE") == []


# 1. receive + upsert accumulates
receive(cur, key(LA), 100); receive(cur, key(LA), 20); conn.commit()
check(on_hand(LA) == 120, "收货两次在同一键上累加为 120")

# 2. move 30 A -> B
g = group(cur, "MOVE")
check(deduct(cur, key(LA), 30), "移库扣减源库位成功")
run(cur, "SQL_ADD_ON_HAND", tenantId=TEN, qty=30, **key(LB))
txn(cur, g, "MOVE_OUT", key(LA), -30); txn(cur, g, "MOVE_IN", key(LB), 30); conn.commit()
check(on_hand(LA) == 90 and on_hand(LB) == 30, "移库后 A=90，B=30")
check(reconcile_empty(), "对账视图：余额 = 流水累计")

# 3. allocation blocks overdraw
check(run(cur, "SQL_ALLOCATE", qty=85, **key(LA)) != [], "分配 85 成功")
check(not deduct(cur, key(LA), 10), "可用仅 5 时扣 10 被拒绝（防超卖）")
check(run(cur, "SQL_ALLOCATE", qty=6, **key(LA)) == [], "超出可用量的分配被拒绝")
rows = run(cur, "SQL_DEDUCT_ALLOCATED", qty=85, **key(LA)); g = group(cur, "SHIP"); txn(cur, g, "SHIP", key(LA), -85)
conn.commit()
check(on_hand(LA) == 5, "按分配发货 85 后在库 5")

# 4. empty rows are cleaned up
g = group(cur, "ADJUST"); check(deduct(cur, key(LB), 30), "B 库位全部扣完"); txn(cur, g, "ADJUST", key(LB), -30); conn.commit()
cur.execute("SELECT count(*) FROM inventory_balance WHERE location_id=%s", (LB,))
check(cur.fetchone()[0] == 0, "数量归零的余额行被清理")
check(reconcile_empty(), "清理后对账仍一致（流水完整保留）")

# 5. idempotency
g1 = receive(cur, key(LA), 7, idem="PDA-001"); conn.commit()
g2 = receive(cur, key(LA), 7, idem="PDA-001"); conn.commit()
check(g1 is not None and g2 is None, "相同幂等键第二次提交不再记账")
check(on_hand(LA) == 12, "幂等：在库 5+7=12，而不是 19")

# 6. LPN generation with own rule, concurrent-safe sequence
r = run(cur, "SQL_NEXT_SEQ", warehouseId=WH)[0]; conn.commit()
prefix, pattern, pad, seq, whc, _ = r
no = pattern.replace("{PREFIX}", prefix).replace("{WH}", whc).replace("{SEQ}", str(seq).zfill(pad))
check(no == "LPLAX100000001", f"LPN 按自有规则生成：{no}")
pal = run(cur, "SQL_INSERT_LPN", tenantId=TEN, warehouseId=WH, lpnNo=no, lpnType="PALLET",
          locationId=LA, customerId=CUST, source="GENERATED")[0][0]
box = run(cur, "SQL_INSERT_LPN", tenantId=TEN, warehouseId=WH, lpnNo="OLD-778899", lpnType="CASE",
          locationId=LA, customerId=CUST, source="MIGRATED")[0][0]
cur.execute("UPDATE lpn SET parent_lpn_id=%s WHERE id=%s", (pal, box))
receive(cur, key(LA, pal), 40); receive(cur, key(LA, box), 12); conn.commit()
try:
    run(cur, "SQL_INSERT_LPN", tenantId=TEN, warehouseId=WH, lpnNo="OLD-778899", lpnType="CASE",
        locationId=LA, customerId=CUST, source="MIGRATED")
    check(False, "重复 LPN 应被拒绝")
except psycopg2.errors.UniqueViolation:
    conn.rollback(); check(True, "重复托盘号被唯一约束拒绝（旧号与新号不会冲突）")

# 7. whole-pallet move incl. child case
tree = run(cur, "SQL_LOCK_LPN_TREE", lpnId=pal)
ids = [t[0] for t in tree]
check(sorted(ids) == sorted([pal, box]), "递归锁定托盘及其子箱 LPN")
bal = run(cur, "SQL_LOCK_LPN_BALANCES", lpnIds=ids)
g = group(cur, "LPN_MOVE")
for b in bal:
    bid, loc, lpn, qty = b[0], b[3], b[4], b[9]
    run(cur, "SQL_MOVE_BALANCE_LOCATION", id=bid, toLocationId=LB)
    txn(cur, g, "MOVE_OUT", key(loc, lpn), -qty); txn(cur, g, "MOVE_IN", key(LB, lpn), qty)
run(cur, "SQL_SET_LPN_LOCATION", ids=ids, locationId=LB); conn.commit()
check(on_hand(LB, pal) == 40 and on_hand(LB, box) == 12 and on_hand(LA, pal) == 0, "整托移动：托盘 40 + 子箱 12 一起到 B")
check(reconcile_empty(), "整托移动后对账一致")

# 8. append-only
for stmt in ["UPDATE inventory_txn SET qty_delta = 1", "DELETE FROM inventory_txn"]:
    try:
        cur.execute(stmt); check(False, stmt)
    except psycopg2.errors.RaiseException as e:
        conn.rollback(); check("append-only" in str(e), f"流水禁止修改：{stmt.split()[0]}")

# 9. concurrency: 2 sessions each try to take 8 of 12 available -> exactly one wins
results = {}
barrier = threading.Barrier(2)


def worker(name, delay):
    c2 = psycopg2.connect(DSN); cu = c2.cursor()
    barrier.wait(); time.sleep(delay)
    ok = deduct(cu, key(LA), 8)
    time.sleep(0.5)  # hold the row lock to force the other session to wait
    c2.commit(); c2.close(); results[name] = ok


ts = [threading.Thread(target=worker, args=("s1", 0)), threading.Thread(target=worker, args=("s2", 0.1))]
[t.start() for t in ts]; [t.join() for t in ts]
check(sorted(results.values()) == [False, True], f"两个会话并发各扣 8（可用 12）：只有一个成功 {results}")
check(on_hand(LA) == 4, "并发后在库 4，未出现负数")

# 10. 50 parallel sessions take 1 each from 4 -> exactly 4 succeed
results.clear()


def taker(i):
    c2 = psycopg2.connect(DSN); cu = c2.cursor()
    results[i] = deduct(cu, key(LA), 1); c2.commit(); c2.close()


ts = [threading.Thread(target=taker, args=(i,)) for i in range(50)]
[t.start() for t in ts]; [t.join() for t in ts]
check(sum(results.values()) == 4, f"50 个并发请求抢 4 件：成功 {sum(results.values())} 个")

# 11. DB CHECK constraint as last line of defence
try:
    cur.execute("UPDATE inventory_balance SET qty_allocated = qty_on_hand + 1"); check(False, "check")
except psycopg2.errors.CheckViolation:
    conn.rollback(); check(True, "数据库约束拒绝 已分配 > 在库")

print(f"\nALL {passed} CHECKS PASSED")
