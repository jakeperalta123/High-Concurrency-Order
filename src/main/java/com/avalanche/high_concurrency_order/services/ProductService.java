package com.avalanche.high_concurrency_order.services;

import java.math.BigDecimal;

public interface ProductService {

    void updateProductPrice(Long productId, BigDecimal newPrice);
}