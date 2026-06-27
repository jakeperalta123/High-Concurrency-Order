package com.avalanche.high_concurrency_order.core.runner;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import com.avalanche.high_concurrency_order.core.constant.RedisKeyConstants;
import com.avalanche.high_concurrency_order.models.entity.Product;
import com.avalanche.high_concurrency_order.models.mapper.ProductMapper;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class StockPreloadRunner implements CommandLineRunner {

    private final ProductMapper productMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;

    public StockPreloadRunner(ProductMapper productMapper, StringRedisTemplate redisTemplate,
            RedissonClient redissonClient) {
        this.productMapper = productMapper;
        this.redisTemplate = redisTemplate;
        this.redissonClient = redissonClient;
    }

    @Override
    public void run(String... args) {
        log.info(">>> [Pre-heating up Redis] Loading product inventory into Redis (6380)...");

        List<Product> products = productMapper.selectList(null);
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(RedisKeyConstants.BLOOM_FILTER_NAME);
        bloomFilter.tryInit(10000L, 0.03);
        if (products != null && !products.isEmpty()) {
            int count = 0;
            for (Product product : products) {
                String redisKey = RedisKeyConstants.PRODUCT_STOCK_PREFIX + product.getId();
                long baseTTL = 7 * 24 * 60;
                long randomOffset = (long) (Math.random() * 12 * 60);
                long finalTTL = baseTTL + randomOffset;
                redisTemplate.opsForValue().set(redisKey, String.valueOf(product.getStock()), finalTTL,
                        TimeUnit.MINUTES);
                bloomFilter.add(product.getId());
                if (count < 50) {
                    log.info(">>> Pre-heating product: {}, inventory: {}", product.getName(), product.getStock());
                    log.info(">>> [BloomFilter] successfully initialized, loaded Product ID grid");
                    count++;
                }

            }
            log.info(">>> [Redis pre-heat] done! Loaded {} products", products.size());
        } else {
            log.warn(">>> [Redis pre-heat] warning: No product in database");
        }
    }

}