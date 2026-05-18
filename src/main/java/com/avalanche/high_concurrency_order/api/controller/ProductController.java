package com.avalanche.high_concurrency_order.api.controller;

import com.avalanche.high_concurrency_order.models.entity.Product;
import com.avalanche.high_concurrency_order.models.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    @Autowired
    private ProductMapper productMapper;

    @GetMapping
    public List<Product> listAll() {
        return productMapper.selectList(null);
    }
}