package com.cloudwms.inventory;

/**
 * 库存业务异常。code 供前端 / PDA / API 调用方识别。
 */
public class InventoryException extends RuntimeException {

    public enum Code {
        INSUFFICIENT_AVAILABLE,   // 可用量不足（防超卖）
        INSUFFICIENT_ALLOCATED,   // 已分配量不足，无法按分配出库 / 释放
        NOT_FOUND,                // 库位、SKU、LPN、批次等不存在
        INVALID_REQUEST,          // 参数不合法：数量 <= 0、跨仓操作、批次缺失等
        LOCATION_BLOCKED,         // 库位被冻结 / 停用
        LPN_CONFLICT,             // LPN 状态不允许该操作（例如含已分配库存时整托移动）
    }

    private final Code code;

    public InventoryException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }

    public static InventoryException insufficientAvailable(Object key, int requested) {
        return new InventoryException(Code.INSUFFICIENT_AVAILABLE,
                "Available quantity is less than " + requested + " for " + key);
    }

    public static InventoryException notFound(String what) {
        return new InventoryException(Code.NOT_FOUND, what + " not found");
    }

    public static InventoryException invalid(String message) {
        return new InventoryException(Code.INVALID_REQUEST, message);
    }
}
