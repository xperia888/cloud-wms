# Cloud WMS · 库存核心（wms-core）

云仓 WMS 的第一个模块：主数据 + LPN 托盘号 + 库存引擎。入库、出库、计费、API 对接等后续模块都只通过 `InventoryService` 改动库存。

技术栈：Java 21 · Spring Boot 3.3 · PostgreSQL 16 · Flyway · JdbcTemplate

## 目录

```
src/main/resources/db/migration/
  V1__master_data.sql        租户、仓库、库区、库位、货主、SKU、单位、条码、批次
  V2__inventory_core.sql     LPN 编号规则、LPN、库存余额、流水组、库存流水、对账视图
src/main/java/com/cloudwms/inventory/
  InventoryService.java      库存引擎（收货 / 移库 / 整托移动 / 调整 / 状态变更 / 分配 / 发货 / 迁移）
  InventoryRepository.java   全部库存 SQL（常量，便于评审）
  LpnService.java            LPN 生成（自有编号规则）、登记、整托锁定
  api/InventoryController.java  REST 接口 /api/v1/...
src/test/java/.../InventoryServiceIT.java   集成测试（Testcontainers，需要 Docker）
tools/verify_sql.py          直接在 PostgreSQL 上回放 Java 中的 SQL，验证核心逻辑
```

## 核心设计

- **余额 + 流水双表**：`inventory_balance` 用于实时查询；`inventory_txn` 只增不改（数据库触发器禁止 UPDATE/DELETE），是对账和计费的唯一依据。每次操作的余额更新与流水在同一事务提交。
- **库存键**：仓 + 库位 + LPN + 货主 + SKU + 批次 + 状态（AVAILABLE / HOLD / QC / DAMAGED）。
- **防超卖**：扣减使用条件 UPDATE（`可用量 >= 扣减量` 才更新），PostgreSQL 在行锁释放后会重新判断条件，因此并发下不会扣成负数；CHECK 约束作为最后保险。
- **幂等**：写操作可带 `Idempotency-Key` 请求头，同一个 key 只记账一次（PDA 重扫、API 重推）。
- **LPN**：编号按仓库在 `lpn_rule` 配置（默认 `{PREFIX}{WH}{SEQ}` → `LPLAX100000001`）；支持托盘下挂箱（父子嵌套），整托移动会连同子箱一起移动；旧系统托盘号以 `source = MIGRATED` 原样保留。
- **对账**：`GET /api/v1/inventory/reconcile` 或 `SELECT * FROM v_inventory_reconcile`，正常应为空，建议每日定时执行并告警。
- **多租户**：所有业务表带 `tenant_id`，一期自用只有一个租户，日后 SaaS 化无需改表。

## 本地运行

```bash
# 1. 准备数据库
createuser -P wms          # 密码 wms
createdb -O wms wms

# 2. 启动（Flyway 会自动建表）
mvn spring-boot:run
# 可用环境变量覆盖：WMS_DB_URL / WMS_DB_USER / WMS_DB_PASSWORD / WMS_PORT

# 3. 集成测试（需要 Docker）
mvn verify
```

## 接口示例

认证尚未接入，暂用请求头 `X-Tenant-Id`、`X-Operator`。

```bash
# 生成托盘号
curl -X POST localhost:8080/api/v1/lpns -H 'X-Tenant-Id: 1' -H 'Content-Type: application/json' \
  -d '{"warehouseId":1,"type":"PALLET","locationId":10,"customerId":1}'

# 收货到托盘
curl -X POST localhost:8080/api/v1/inventory/receive \
  -H 'X-Tenant-Id: 1' -H 'X-Operator: pda-07' -H 'Idempotency-Key: ASN1001-L1-P1' \
  -H 'Content-Type: application/json' \
  -d '{"key":{"warehouseId":1,"locationId":10,"lpnId":1,"customerId":1,"skuId":5},"qty":48,"refType":"ASN","refId":"ASN1001"}'

# 整托移库
curl -X POST localhost:8080/api/v1/lpns/1/move -H 'X-Tenant-Id: 1' -H 'X-Operator: pda-07' \
  -H 'Content-Type: application/json' -d '{"toLocationId":22}'

# 查询库存
curl 'localhost:8080/api/v1/inventory?warehouseId=1&skuId=5' -H 'X-Tenant-Id: 1'
```

## 验证状态

- 数据库迁移脚本已在 PostgreSQL 16 上执行通过。
- `tools/verify_sql.py` 把 Java 源码中的 SQL 常量原样取出，在 PostgreSQL 16 上按服务层的调用顺序回放，24 项检查全部通过：收货累加、移库、分配后防超卖、按分配发货、归零清理、幂等、LPN 生成与唯一性、整托连子箱移动、流水禁止修改、双会话与 50 并发扣减、CHECK 约束。
  ```bash
  pip install psycopg2-binary
  python3 tools/verify_sql.py src/main/java/com/cloudwms
  ```
- **尚未执行 `mvn verify`**：开发环境无法访问 Maven 中央仓库，Java 代码未经编译。首次在本地构建时请先运行 `mvn verify`。

## 下一步

1. 旧系统迁移脚本：读取旧库的库位 / 批次 / 托盘号 / 库存，调用 `migrateOpening` 导入。
2. 小包出库纵切：订单 → 分配 → 拣货 → 打包称重 → UPS 面单 → 发货扣减。
3. 入库模块（ASN、收货、上架）与 PDA 接口。
