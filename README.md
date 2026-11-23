# Cloud WMS（wms-core）

云仓 WMS 后端。目前包含：

1. **库存核心**：主数据、LPN 托盘号、库存引擎（余额 + 只增不改的流水）。
2. **小包出库**：标准出库单 API → 分配 → 拣货 → 打包称重 → Rate Shopping → 打面单发货 → 扣库存 → 计费事件。
3. **承运商适配层**：统一接口 + UPS / FedEx / USPS 模拟器（真实 API 待凭证到位后接入）。

技术栈：Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · JdbcTemplate

## 目录

```
src/main/resources/db/migration/
  V1__master_data.sql        租户、仓库、库区、库位、货主、SKU、单位、条码、批次
  V2__inventory_core.sql     LPN 编号规则、LPN、库存余额、流水组、库存流水、对账视图
  V3__outbound_parcel.sql    承运商 / 服务 / 账号、发运规则、箱型、出库单、分配、包裹、发运、报价、计费事件
src/main/resources/db/dev/
  V1000__dev_seed.sql        测试数据（仅 dev 配置加载）
src/main/java/com/cloudwms/
  inventory/                 库存引擎、LPN、库存 REST 接口
  carrier/                   CarrierAdapter 接口、模拟器、RateShopper 比价
  outbound/                  出库服务与 REST 接口
  console/                   控制台用的只读查询接口
src/main/resources/static/index.html   云仓控制台演示网页（无需构建，随系统启动）
src/test/java/...            集成测试（Testcontainers，需要 Docker）
tools/                       在 PostgreSQL 上回放 Java 中 SQL 的验证脚本
```

## 核心设计

**库存**
- **余额 + 流水双表**：`inventory_balance` 用于实时查询；`inventory_txn` 只增不改（触发器禁止 UPDATE/DELETE），是对账和计费的唯一依据。余额与流水在同一事务提交。
- **库存键**：仓 + 库位 + LPN + 货主 + SKU + 批次 + 状态（AVAILABLE / HOLD / QC / DAMAGED）。
- **防超卖**：扣减与分配使用条件 UPDATE（可用量足够才更新）；CHECK 约束作为最后保险。
- **幂等**：写操作可带 `Idempotency-Key`，同一个 key 只记账一次。
- **LPN**：编号按仓库在 `lpn_rule` 配置（`{PREFIX}{WH}{SEQ}` → `LPLAX100001001`）；托盘下可挂箱，整托移动连子箱一起移；旧系统托盘号以 `MIGRATED` 原样保留。
- **对账**：`v_inventory_reconcile` 正常为空，建议每日定时检查。

**小包出库**
- **状态**：`NEW → ALLOCATED → PICKED → PACKED → SHIPPED`，发货前可取消（释放分配）。
- **建单幂等**：同一货主 + 来源 + 外部单号重复推送，返回原订单（HTTP 200，`duplicate: true`）。
- **分配**：只从可拣库位分配，先到期先出（FEFO）；整单全部满足才成功。候选库存行加锁、按 SKU 顺序加锁，并发订单不会超分配也不会死锁。整托储位的货需先补货到拣货位（补货任务在后续迭代）。
- **打包**：每箱商品合计必须与已拣数量一致；箱型或手填尺寸 + 称重。
- **Rate Shopping**：每家承运商一个虚拟线程并发询价，超时（默认 3 秒）或报错的承运商跳过、不阻塞；按货主 `ship_rule`（最低价 / 最快、允许的服务、最长时效）选最优；所有报价存入 `rate_quote`。货主自带承运商账号（BYOA）优先于仓库账号。
- **发货三步走**：① 事务内登记 PENDING 发运（防重复打单）→ ② 事务外调用承运商打单 → ③ 事务内写追踪号、按分配扣库存、置 SHIPPED、写计费事件；第 ③ 步失败会自动作废刚打出的面单。
- **计费事件**：发货时写入 `billable_event`（订单操作费、拣货件数、包裹数、运费成本），同一事件只记一次，P3 月结时汇总成 Invoice。

## 在 GitHub 上运行

**Codespaces（浏览器里运行整个系统）**
1. 仓库页面 → 绿色 **Code** 按钮 → **Codespaces** → **Create codespace on main**。首次创建约 3–5 分钟（安装 Java、Maven、PostgreSQL 并下载依赖）。
2. 在下方终端运行：`mvn spring-boot:run -Dspring-boot.run.profiles=dev`
3. 启动后右下角会提示端口 8080 已转发，点 **Open in Browser**（或在 **Ports** 标签里点 8080 的地址），就能看到**云仓控制台**网页：
   - 出库单：新建测试订单，按流程点“分配库存 → 确认拣货 → 完成打包 → 比价 → 打单发货”，发货后显示 4×6 模拟面单；
   - 库存：按 SKU 汇总（在库 / 可用 / 可拣）与按库位、托盘号的明细；
   - 计费：发货自动产生的计费事件与汇总。
   开发人员也可以打开 `api-examples.http`，逐段点击 **Send Request** 直接调用接口。
4. 用完在 github.com/codespaces 停止或删除，避免消耗免费额度。

**Actions（自动编译测试）**：每次推送到 `main` 自动执行 `.github/workflows/ci.yml`：
- `mvn verify`：编译 + 全部集成测试；
- 在干净的 PostgreSQL 上执行迁移脚本与测试数据，并运行 `tools/` 下的 SQL 回放检查。

结果在仓库的 **Actions** 页面查看。

## 本地运行

```bash
# 1. 准备数据库
createuser -P wms          # 密码 wms
createdb -O wms wms

# 2. 带测试数据与承运商模拟器启动，浏览器打开 http://localhost:8080 即为控制台网页
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# 生产 / 真实承运商：不加 dev 配置，wms.carriers.mode=live（真实适配器尚未接入）
# 可用环境变量覆盖：WMS_DB_URL / WMS_DB_USER / WMS_DB_PASSWORD / WMS_PORT

# 3. 集成测试（需要 Docker）
mvn verify
```

### 测试数据（dev）

- 租户 `SELF`，试点仓 `LAX1`（Ontario, CA 91761）：收货门、6 个整托储位（不可直接拣）、7 个拣货货架位、打包台、发货门。
- 货主 `ACME`（户外用品，4 个 SKU）、`BETA`（美妆，3 个 SKU，其中 2 个批次 + 效期管理）。
- 5 个托盘（含 1 个模拟旧系统迁移的 `OLD-000451`），期初库存 2,104 件，对账一致。
- 箱型 BOX-S / M / L / XL、POLY-M；UPS / FedEx / USPS 沙箱账号；ACME 最低价 + 运费加价 8%，BETA 最低价且 3 天内送达。
- 5 张状态为 NEW 的示例出库单（Shopify / API 来源，含住宅与商业地址、指定 UPS Ground 的订单）。

模拟器测试钩子：收件邮编 `00000` 模拟地址无效，`99999` 模拟承运商超时。

## 接口示例

认证尚未接入，暂用请求头 `X-Tenant-Id`、`X-Operator`。dev 测试数据中租户、仓库、货主 ACME 的 ID 都是 1。

```bash
H='-H X-Tenant-Id:1 -H X-Operator:pack-01 -H Content-Type:application/json'

# 标准出库单（任何订单来源都转换成这个格式）；返回体中的 id 即下面的 {id}
curl -X POST localhost:8080/api/v1/outbound-orders $H -d '{
  "warehouseId":1, "customerId":1, "source":"SHOPIFY", "externalOrderNo":"SHOP-2001",
  "shipTo":{"name":"Sam Ortiz","address1":"1 Market St","city":"San Francisco","state":"CA","zip":"94105","country":"US","residential":true},
  "lines":[{"skuCode":"ACME-HEADLAMP","qty":2}]}'

curl -X POST localhost:8080/api/v1/outbound-orders/{id}/allocate $H
curl -X POST localhost:8080/api/v1/outbound-orders/{id}/pick $H
# skuId 取自订单返回体 lines[].skuId
curl -X POST localhost:8080/api/v1/outbound-orders/{id}/pack $H -d '{"packages":[
  {"boxType":"BOX-S","weightLb":1.2,"items":[{"skuId":<headlamp skuId>,"qty":2}]}]}'
curl -X POST localhost:8080/api/v1/outbound-orders/{id}/rates $H       # 查看全部报价与最优服务
curl -X POST localhost:8080/api/v1/outbound-orders/{id}/ship $H        # 按最优服务打单发货
#   或指定服务：-d '{"carrierCode":"UPS","serviceCode":"UPS_GROUND"}'
curl localhost:8080/api/v1/outbound-orders/{id} $H                     # 订单、包裹与追踪号
curl localhost:8080/api/v1/packages/{packageId}/label $H                # ZPL 面单，可直接发给热敏打印机

# 库存
curl 'localhost:8080/api/v1/inventory?warehouseId=1' -H 'X-Tenant-Id: 1'
curl -X POST localhost:8080/api/v1/lpns/{lpnId}/move $H -d '{"toLocationId":{locationId}}'   # 整托移库
curl localhost:8080/api/v1/inventory/reconcile -H 'X-Tenant-Id: 1'
```

## 验证状态

| 检查 | 结果 |
| --- | --- |
| 全部迁移脚本 + 测试数据在 PostgreSQL 16 上执行 | 通过，期初库存对账一致 |
| `tools/verify_sql.py`：库存引擎 SQL 回放（收货、移库、防超卖、幂等、LPN、整托移动、流水不可改、并发扣减） | 24 / 24 通过 |
| `tools/verify_outbound.py`：出库 SQL 回放（建单幂等、FEFO 分配、拣货打包、比价上下文、发货扣库存、计费事件、取消释放、库存不足回滚、两单并发分配） | 36 / 36 通过 |
| 承运商模拟器 + RateShopper（单独 javac 编译运行：最低价 / 最快 / 时效限制 / 允许服务 / 地址无效 / 超时跳过 / 多箱面单 / 追踪号格式） | 15 / 15 通过 |
| `mvn verify`（真实 Spring 依赖编译 + Testcontainers 集成测试，GitHub Actions） | 见 Actions 页面 |
| `tools/smoke_test.sh`：CI 中用 jar 真实启动系统，打开控制台网页并通过 HTTP 走完整条出库流程 | 见 Actions 页面 |

```bash
pip install psycopg2-binary
python3 tools/verify_sql.py src/main/java/com/cloudwms                       # 使用数据库 wms（会清空数据）
python3 tools/verify_outbound.py src/main/java/com/cloudwms "dbname=wms_dev user=wms password=wms host=localhost"
```

## 下一步

1. 接入真实承运商：UPS（OAuth + Rating / Shipping API）、FedEx、USPS，各实现一个 `CarrierAdapter`，在 `CarrierRegistry` 中按 `live` 模式注册；凭证放入密钥管理系统。
2. PDA 逐行扫码拣货、补货任务（整托储位 → 拣货位）、每日 Manifest / SCAN Form。
3. 入库模块（ASN、收货生成 LPN、上架）。
4. 旧系统迁移脚本（暂缓）：读取旧库的库位 / 批次 / 托盘号 / 库存，调用 `migrateOpening` 导入。
