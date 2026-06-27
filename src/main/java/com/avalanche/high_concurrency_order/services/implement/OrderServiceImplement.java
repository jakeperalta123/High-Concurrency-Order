package com.avalanche.high_concurrency_order.services.implement;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.redisson.api.RedissonClient;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RLock;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import com.avalanche.high_concurrency_order.services.OrderService;

import jakarta.annotation.PostConstruct;

import com.avalanche.high_concurrency_order.models.mapper.OrderMapper;
import com.avalanche.high_concurrency_order.models.mapper.ProductMapper;
import com.avalanche.high_concurrency_order.models.mapper.StockLogMapper;
import com.avalanche.high_concurrency_order.core.constant.RedisKeyConstants;
import com.avalanche.high_concurrency_order.core.exception.BusinessException;
import com.avalanche.high_concurrency_order.models.entity.Order;
import com.avalanche.high_concurrency_order.models.entity.Product;
import com.avalanche.high_concurrency_order.models.entity.StockLog;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderServiceImplement implements OrderService {

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final StockLogMapper stockLogMapper;
    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private DefaultRedisScript<Long> deductStockScript;

    @PostConstruct
    public void init() {
        deductStockScript = new DefaultRedisScript<>();
        deductStockScript.setLocation(new ClassPathResource("lua/deduct_stock.lua"));
        deductStockScript.setResultType(Long.class);
    }

    private Long executeLuaDeduct(String redisKey, Integer quantity) {
        return redisTemplate.execute(deductStockScript, Collections.singletonList(redisKey), String.valueOf(quantity));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void placeOrderV1_DBOnly(Long productId, Integer quantity) {
        Product product = productMapper.selectProductByIdForUpdate(productId);

        if (product == null) {
            throw new BusinessException(400, "Product doesn't exist");
        }

        if (product.getStock() < quantity) {
            throw new BusinessException(400, "Insufficient inventory(DB)");
        }

        productMapper.deductStock(productId, quantity);

        Order order = new Order();
        order.setUserId(1L);
        order.setProductId(productId);
        order.setQuantity(quantity);
        order.setAmount(product.getPrice().multiply(new java.math.BigDecimal(quantity)));
        order.setOrderSn(java.util.UUID.randomUUID().toString().replace("-", ""));
        orderMapper.insert(order);
    }

    @Override
    public void placeOrderV2_CoarseLock(Long userId, Long productId, Integer quantity) {
        String lockKey = "order:lock:all:" + productId;
        RLock lock = redissonClient.getLock(lockKey);
        String orderSn = java.util.UUID.randomUUID().toString().replace("-", "");
        try {
            if (lock.tryLock(5, -1, TimeUnit.SECONDS)) {
                String redisKey = "product:stock:" + productId;
                Long remainStock = redisTemplate.opsForValue().decrement(redisKey, quantity);
                if (remainStock == null || remainStock < 0) {
                    redisTemplate.opsForValue().increment(redisKey, quantity);
                    throw new BusinessException(400, "Insufficient inventory(Redis)");
                }

                this.handleDBPersistence(userId, productId, quantity, remainStock, orderSn);
            } else {
                throw new BusinessException(400, "Request timeout, try again later");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public void placeOrder(Long userId, Long productId, Integer quantity) {
        String redisKey = "product:stock:" + productId;

        Long remainStock = executeLuaDeduct(redisKey, quantity);

        if (remainStock < 0) {
            throw new BusinessException(400,
                    remainStock == -1 ? "Product does not exist" : "Insufficient Inventory(Redis)");
        }

        String lockKey = "order:lock:db:" + productId;
        RLock lock = redissonClient.getLock(lockKey);
        String orderSn = java.util.UUID.randomUUID().toString().replace("-", "");
        try {
            if (lock.tryLock(5, -1, TimeUnit.SECONDS)) {
                this.handleDBPersistence(userId, productId, quantity, remainStock, orderSn);
            }
        } catch (InterruptedException e) {
            log.error(">>> [Lock] Lock acquisition interrupted", e);
            redisTemplate.opsForValue().increment(redisKey, quantity);
            Thread.currentThread().interrupt();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.info(">>> [Lock] Lock released: {}", Thread.currentThread().getName());
            }
        }
    }

    @Override
    public Long deductRedisStock(Long productId, Integer quantity) {
        String redisKey = RedisKeyConstants.PRODUCT_STOCK_PREFIX + productId;
        String lockKey = RedisKeyConstants.PRODUCT_LOCK_PREFIX + productId;
        Long remainStock = executeLuaDeduct(redisKey, quantity);
        RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter(RedisKeyConstants.BLOOM_FILTER_NAME);
        if (!bloomFilter.contains(productId)) {
            log.warn("🛑 [BloomFilter] intercepted Illegal Product Id request: {}", productId);
            return -1L;
        }
        if (remainStock >= 0 || remainStock == -2) {
            if (remainStock >= 0) {
                log.info("[Redis pre-deduct success]Product: {}, Remaining inventory: {}", productId, remainStock);
            }
            return remainStock;
        }

        if (remainStock == -1) {
            log.warn("[Cache miss] Redis can't find product key: {}, dispatching distributed lock to reconstruct...",
                    redisKey);
            RLock lock = redissonClient.getLock(lockKey);

            try {
                if (lock.tryLock(3, 5, TimeUnit.SECONDS)) {
                    String currentStock = redisTemplate.opsForValue().get(redisKey);
                    if (currentStock != null) {
                        return executeLuaDeduct(redisKey, quantity);
                    }
                    Product product = productMapper.selectById(productId);

                    if (product == null) {
                        redisTemplate.opsForValue().set(redisKey, "", 2, TimeUnit.MINUTES);
                        log.error(
                                "[Defense penatration] No such product in MySQL, inserting NULL place holder, productId: {}",
                                productId);
                        return -1L;
                    }

                    long finaltTTL = 30 + (long) (Math.random() * 5);
                    redisTemplate.opsForValue().set(redisKey, String.valueOf(product.getStock()), finaltTTL,
                            TimeUnit.MINUTES);
                    log.info("[Cache successfully restored] Product: {} Inventory: {} write back into Redis", productId,
                            product.getStock());

                    return executeLuaDeduct(redisKey, quantity);
                } else {
                    Thread.sleep(100);
                    return deductRedisStock(productId, quantity);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }
        return remainStock;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleDBPersistence(Long userId, Long productId, Integer quantity, Long remainStock, String orderSn) {

        int affectedRows = productMapper.deductStock(productId, quantity);
        if (affectedRows == 0) {
            throw new BusinessException(400, "System busy, Inventory not equal between DB and Redis");
        }

        Product product = productMapper.selectById(productId);

        Order order = new Order();
        order.setUserId(userId);
        order.setProductId(productId);
        order.setQuantity(quantity);
        order.setAmount(product.getPrice().multiply(new java.math.BigDecimal(quantity)));
        order.setOrderSn(orderSn);
        orderMapper.insert(order);

        StockLog stockLog = new StockLog();
        stockLog.setProductId(productId);
        stockLog.setStockChange(-quantity);
        stockLog.setTransactionId(order.getOrderSn());
        stockLogMapper.insert(stockLog);

        log.info(">>> [SUCCESS] Successfully placed order! Order number: {}, Redis remaining inventory: {}",
                order.getOrderSn(), remainStock);

    }
}