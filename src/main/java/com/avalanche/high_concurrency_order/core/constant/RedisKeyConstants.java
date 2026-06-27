package com.avalanche.high_concurrency_order.core.constant;

public class RedisKeyConstants {
    public static final String PRODUCT_STOCK_PREFIX = "product:stock:";
    public static final String PRODUCT_LOCK_PREFIX = "product:lock:rebuild:";
    public static final String SECKILL_VERIFY_CODE_PREFIX = "seckill:verifyCode:";
    public static final String SECKILL_PATH_PREFIX = "seckill:path:";
    public static final String BLOOM_FILTER_NAME = "seckill:bloom:products";
}