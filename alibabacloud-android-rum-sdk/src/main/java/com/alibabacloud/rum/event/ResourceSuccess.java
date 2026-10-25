package com.alibabacloud.rum.event;

/** Resource 结果；与状态码独立，不根据 HTTP 状态范围推导。 */
public enum ResourceSuccess {
    UNKNOWN(com.alibabacloud.rum.core.event.ResourceSuccess.UNKNOWN),
    FAILED(com.alibabacloud.rum.core.event.ResourceSuccess.FAILED),
    SUCCESS(com.alibabacloud.rum.core.event.ResourceSuccess.SUCCESS);

    private final com.alibabacloud.rum.core.event.ResourceSuccess value;

    ResourceSuccess(com.alibabacloud.rum.core.event.ResourceSuccess value) {
        this.value = value;
    }

    /** UNKNOWN、FAILED、SUCCESS 分别为 -1、0、1。 */
    public int getValue() {
        return value.getValue();
    }

    com.alibabacloud.rum.core.event.ResourceSuccess toInternal() {
        return value;
    }
}
