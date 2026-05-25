package com.avalanche.high_concurrency_order.core.runner;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.access.method.P;
import org.springframework.stereotype.Component;

import com.avalanche.high_concurrency_order.models.entity.Product;
import com.avalanche.high_concurrency_order.models.mapper.ProductMapper;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class StockPreloadRunner implements CommandLineRunner {

    private final ProductMapper productMapper;
    private final StringRedisTemplate redisTemplate;

    public StockPreloadRunner(ProductMapper productMapper, StringRedisTemplate redisTemplate) {
        this.productMapper = productMapper;
        this.redisTemplate = redisTemplate;
    }

    @Override
    public void run(String... args) {
        log.info(">>> [Pre-heating up Redis] Loading product inventory into Redis (6380)...");

        List<Product> products = productMapper.selectList(null);

        if (products != null && !products.isEmpty()) {
            int count = 0;
            for (Product product : products) {
                String redisKey = "product:stock:" + product.getId();
                redisTemplate.opsForValue().set(redisKey, String.valueOf(product.getStock()));
                if (count < 50) {
                    log.info(">>> Pre-heating product: {}, inventory: {}", product.getName(), product.getStock());
                    count++;
                }

            }
            log.info(">>> [Redis pre-heat] done! Loaded {} products", products.size());
        } else {
            log.warn(">>> [Redis pre-heat] warning: No product in database");
        }
    }

}