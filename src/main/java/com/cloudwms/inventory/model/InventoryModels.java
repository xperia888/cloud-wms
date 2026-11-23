package com.cloudwms.inventory.model;

/**
 * 库存核心的值对象。全部为不可变 record，方便在服务层之间传递。
 */
public final class InventoryModels {

    private InventoryModels() {
    }

    /** 库存状态：与 inventory_balance.inv_status 一致 */
    public enum InventoryStatus { AVAILABLE, HOLD, QC, DAMAGED }

    /** 流水类型：与 inventory_txn.txn_type 一致 */
    public enum TxnType {
        RECEIVE, MOVE_OUT, MOVE_IN, ADJUST, COUNT_ADJUST,
        STATUS_OUT, STATUS_IN, SHIP, RETURN_RECEIVE, MIGRATION
    }

    /**
     * 库存余额的唯一键：仓 + 库位 + LPN + 货主 + SKU + 批次 + 状态。
     * lpnId / lotId 可为 null（散货 / 非批次管理商品）。
     */
    public record StockKey(
            long warehouseId,
            long locationId,
            Long lpnId,
            long customerId,
            long skuId,
            Long lotId,
            InventoryStatus status) {

        public StockKey {
            if (status == null) {
                status = InventoryStatus.AVAILABLE;
            }
        }

        public StockKey withLocation(long newLocationId) {
            return new StockKey(warehouseId, newLocationId, lpnId, customerId, skuId, lotId, status);
        }

        public StockKey withLpn(Long newLpnId) {
            return new StockKey(warehouseId, locationId, newLpnId, customerId, skuId, lotId, status);
        }

        public StockKey withStatus(InventoryStatus newStatus) {
            return new StockKey(warehouseId, locationId, lpnId, customerId, skuId, lotId, newStatus);
        }
    }

    /**
     * 一次库存操作的上下文：谁、为什么、来源单据、幂等键。
     * idempotencyKey 相同的请求只会生效一次（PDA 重复扫描、API 重复推送）。
     */
    public record OpContext(
            long tenantId,
            String operator,
            String idempotencyKey,
            String refType,
            String refId,
            String reason) {

        public OpContext {
            if (operator == null || operator.isBlank()) {
                throw new IllegalArgumentException("operator is required");
            }
        }
    }

    /** 一次库存操作的结果：流水组 ID；duplicate = true 表示该幂等键已处理过，本次未重复记账 */
    public record OpResult(long groupId, boolean duplicate) {
    }

    /** 余额查询结果 */
    public record BalanceRow(
            long id,
            long warehouseId,
            long locationId,
            String locationCode,
            Long lpnId,
            String lpnNo,
            long customerId,
            long skuId,
            String skuCode,
            Long lotId,
            String lotNo,
            InventoryStatus status,
            int qtyOnHand,
            int qtyAllocated) {

        public int qtyAvailable() {
            return qtyOnHand - qtyAllocated;
        }
    }

    /** 对账差异行（v_inventory_reconcile） */
    public record ReconcileDiff(
            long locationId,
            Long lpnId,
            long customerId,
            long skuId,
            Long lotId,
            InventoryStatus status,
            int qtyBalance,
            int qtyFromTxn) {
    }
}
