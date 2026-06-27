package com.avalanche.high_concurrency_order.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SeckillOrderMessage {
    private Long userId;
    private Long productId;
    private Integer quantity;
    private Long remainStock;
    private String orderSn;
}