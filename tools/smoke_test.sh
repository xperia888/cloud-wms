#!/usr/bin/env bash
# 冒烟测试：对已启动的系统（dev 配置，含测试数据与承运商模拟器）走一遍控制台用到的全部接口。
# 用法：tools/smoke_test.sh [http://localhost:8080]
set -euo pipefail
BASE="${1:-http://localhost:8080}"
API="$BASE/api/v1"
H=(-H "X-Tenant-Id: 1" -H "X-Operator: smoke" -H "Content-Type: application/json")
pass=0
ok() { pass=$((pass + 1)); echo "ok  - $1"; }
fail() { echo "FAIL: $1"; exit 1; }
j() { python3 -c "import sys,json; d=json.load(sys.stdin); print($1)"; }

for i in $(seq 1 60); do
  curl -sf "${H[@]}" "$API/ref/warehouses" >/dev/null && break
  sleep 2
  [ "$i" = 60 ] && fail "application did not start"
done

curl -sf "$BASE/" | grep -q "云仓控制台" && ok "首页（控制台网页）可访问" || fail "index.html"

[ "$(curl -sf "${H[@]}" "$API/ref/warehouses" | j "d[0]['code']")" = "LAX1" ] && ok "仓库列表" || fail "warehouses"
[ "$(curl -sf "${H[@]}" "$API/ref/customers" | j "len(d)")" = "2" ] && ok "货主列表" || fail "customers"
[ "$(curl -sf "${H[@]}" "$API/ref/skus?warehouseId=1" | j "len(d)")" = "7" ] && ok "SKU 列表（含可拣数量）" || fail "skus"
[ "$(curl -sf "${H[@]}" "$API/ref/box-types" | j "len(d)")" = "5" ] && ok "箱型" || fail "box types"
[ "$(curl -sf "${H[@]}" "$API/outbound-orders?warehouseId=1" | j "len(d) >= 5")" = "True" ] && ok "订单列表" || fail "orders list"
[ "$(curl -sf "${H[@]}" "$API/inventory?warehouseId=1&limit=1000" | j "len(d) >= 13")" = "True" ] && ok "库存明细" || fail "inventory"

EXT="SMOKE-$(date +%s)"
ORDER=$(curl -sf "${H[@]}" -X POST "$API/outbound-orders" -d "{
  \"warehouseId\":1,\"customerId\":1,\"source\":\"CONSOLE\",\"externalOrderNo\":\"$EXT\",
  \"shipTo\":{\"name\":\"Sam Ortiz\",\"address1\":\"1 Market St\",\"city\":\"San Francisco\",\"state\":\"CA\",\"zip\":\"94105\",\"country\":\"US\",\"residential\":true},
  \"lines\":[{\"skuCode\":\"ACME-HEADLAMP\",\"qty\":2}]}")
OID=$(echo "$ORDER" | j "d['id']"); SKU=$(echo "$ORDER" | j "d['lines'][0]['skuId']")
ok "建单 id=$OID"
[ "$(curl -s -o /dev/null -w '%{http_code}' "${H[@]}" -X POST "$API/outbound-orders" -d "{
  \"warehouseId\":1,\"customerId\":1,\"source\":\"CONSOLE\",\"externalOrderNo\":\"$EXT\",
  \"shipTo\":{\"name\":\"x\",\"address1\":\"1 Market St\",\"city\":\"SF\",\"state\":\"CA\",\"zip\":\"94105\"},
  \"lines\":[{\"skuCode\":\"ACME-HEADLAMP\",\"qty\":2}]}")" = "200" ] && ok "重复外部单号返回 200（不重复建单）" || fail "idempotent create"

[ "$(curl -sf "${H[@]}" -X POST "$API/outbound-orders/$OID/allocate" | j "d['status']")" = "ALLOCATED" ] && ok "分配" || fail "allocate"
[ "$(curl -sf "${H[@]}" -X POST "$API/outbound-orders/$OID/pick" | j "d['status']")" = "PICKED" ] && ok "拣货" || fail "pick"
[ "$(curl -sf "${H[@]}" -X POST "$API/outbound-orders/$OID/pack" -d "{\"packages\":[{\"boxType\":\"BOX-S\",\"weightLb\":1.2,\"items\":[{\"skuId\":$SKU,\"qty\":2}]}]}" | j "d['status']")" = "PACKED" ] && ok "打包" || fail "pack"
RATES=$(curl -sf "${H[@]}" -X POST "$API/outbound-orders/$OID/rates")
[ "$(echo "$RATES" | j "len(d['quotes']) >= 5 and d['best'] is not None")" = "True" ] && ok "比价：$(echo "$RATES" | j "d['best']['carrierCode'] + ' ' + d['best']['serviceCode'] + ' \$' + str(d['best']['amount'])")" || fail "rates"
SHIP=$(curl -sf "${H[@]}" -X POST "$API/outbound-orders/$OID/ship")
ok "打单发货：$(echo "$SHIP" | j "d['carrierCode'] + ' ' + ','.join(d['trackingNumbers'])")"
VIEW=$(curl -sf "${H[@]}" "$API/outbound-orders/$OID")
[ "$(echo "$VIEW" | j "d['status']")" = "SHIPPED" ] && ok "订单状态 SHIPPED" || fail "shipped status"
PKG=$(echo "$VIEW" | j "d['packages'][0]['id']")
curl -sf "${H[@]}" "$API/packages/$PKG/label" | grep -q '^\^XA' && ok "取回 ZPL 面单" || fail "label"
[ "$(curl -sf "${H[@]}" "$API/outbound-orders/$OID/shipments" | j "d[0]['status']")" = "LABELED" ] && ok "发运记录" || fail "shipments"
[ "$(curl -sf "${H[@]}" "$API/billable-events?warehouseId=1" | j "len([e for e in d if e.get('order_no')])")" -ge 4 ] && ok "计费事件" || fail "billable events"

BAD=$(curl -sf "${H[@]}" -X POST "$API/outbound-orders" -d "{
  \"warehouseId\":1,\"customerId\":1,\"source\":\"CONSOLE\",\"externalOrderNo\":\"$EXT-SHORT\",
  \"shipTo\":{\"name\":\"x\",\"address1\":\"500 Main St\",\"city\":\"Boulder\",\"state\":\"CO\",\"zip\":\"80302\"},
  \"lines\":[{\"skuCode\":\"ACME-CHAIR\",\"qty\":30}]}" | j "d['id']")
CODE=$(curl -s -o /tmp/short.json -w '%{http_code}' "${H[@]}" -X POST "$API/outbound-orders/$BAD/allocate")
[ "$CODE" = "409" ] && [ "$(j "d['code']" < /tmp/short.json)" = "INSUFFICIENT_STOCK" ] && ok "库存不足返回 409 INSUFFICIENT_STOCK" || fail "short allocation ($CODE)"

[ "$(curl -sf "${H[@]}" "$API/inventory/reconcile" | j "len(d)")" = "0" ] && ok "库存对账一致" || fail "reconcile"
echo; echo "ALL $pass SMOKE CHECKS PASSED"
