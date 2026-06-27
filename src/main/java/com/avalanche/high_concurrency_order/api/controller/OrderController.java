package com.avalanche.high_concurrency_order.api.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.avalanche.high_concurrency_order.api.response.Result;
import com.avalanche.high_concurrency_order.services.OrderService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping("/v3")
    public Result<String> placeOrderV3(
            @RequestParam Long productId,
            @RequestParam Integer quantity,
            @RequestAttribute("userId") Long userId) {
        orderService.placeOrder(userId, productId, quantity);
        return Result.success("V3 success");
    }

    @PostMapping("/v1")
    public Result<String> placeOrderV1(@RequestParam Long productId, @RequestParam Integer quantity) {
        orderService.placeOrderV1_DBOnly(productId, quantity);
        return Result.success("V1 success");
    }

    @PostMapping("/v2")
    public Result<String> placeOrderV2(
            @RequestParam Long productId,
            @RequestParam Integer quantity,
            @RequestAttribute("userId") Long userId) {
        orderService.placeOrderV2_CoarseLock(userId, productId, quantity);
        return Result.success("V2 success");
    }

}