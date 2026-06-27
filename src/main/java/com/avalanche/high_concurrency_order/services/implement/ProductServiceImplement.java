package com.avalanche.high_concurrency_order.services.implement;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.avalanche.high_concurrency_order.models.mapper.ProductMapper;
import com.avalanche.high_concurrency_order.services.ProductService;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ProductServiceImplement implements ProductService {

    private final ProductMapper productMapper;
    private final StringRedisTemplate redisTemplate;
    private final ExecutorService delayedDeletePool;

    public ProductServiceImplement(
            ProductMapper productMapper,
            StringRedisTemplate redisTemplate,
            @Qualifier("delayedDeletePool") ExecutorService delayedDeletePool) {
        this.productMapper = productMapper;
        this.redisTemplate = redisTemplate;
        this.delayedDeletePool = delayedDeletePool;
    }

    public void updateProductPrice(Long productId, BigDecimal newPrice) {
        String redisKey = "product:price:" + productId;
        redisTemplate.delete(redisKey);
        productMapper.updateProductPriceById(newPrice, productId);
        log.info("Product{} price updated to {}", productId, newPrice);

        CompletableFuture.runAsync(() -> {
            boolean interrupted = false;
            try {
                log.info(">>>[background] Starting to sleep for 500ms... productId:{}", productId);
                Thread.sleep(500);
            } catch (InterruptedException e) {
                interrupted = true;
                log.error("delayed double delete interrupted, productId:{}", productId, e);
            } finally {
                redisTemplate.delete(redisKey);
                log.info(">>>>[background] delayed double delete finished! Key: {}", redisKey);
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, delayedDeletePool)
                .exceptionally(throwable -> {
                    log.error(">>>[Warning] background double delte failed! Key: {}", redisKey, throwable);
                    return null;
                });
    }
}