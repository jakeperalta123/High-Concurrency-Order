package com.avalanche.high_concurrency_order.services;

public interface OrderService {

    void placeOrder(Long productId, Integer quantity);

}