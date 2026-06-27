package com.avalanche.high_concurrency_order.services;

public interface OrderService {

    void placeOrder(Long userId, Long productId, Integer quantity);

    void placeOrderV1_DBOnly(Long productId, Integer quantity);

    void placeOrderV2_CoarseLock(Long userId, Long productId, Integer quantity);

    Long deductRedisStock(Long productId, Integer quantity);

    void handleDBPersistence(Long userId, Long productId, Integer quantity, Long remainStock, String orderSn);
}