package com.avalanche.high_concurrency_order.api.controller;

import com.avalanche.high_concurrency_order.api.response.Result;
import com.avalanche.high_concurrency_order.models.entity.Product;
import com.avalanche.high_concurrency_order.services.OrderService;
import com.avalanche.high_concurrency_order.models.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrderService orderService;

    @GetMapping
    public Result<List<Product>> listAll() {
        return Result.success(productMapper.selectList(null));
    }

    @PostMapping("/order")
    public Result<String> placeOrder(@RequestParam Long productId, @RequestParam Integer quantity) {
        orderService.placeOrder(productId, quantity);
        return Result.success("Order placed successfully!");
    }
}