package com.avalanche.high_concurrency_order.services.implement;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.avalanche.high_concurrency_order.services.OrderService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.avalanche.high_concurrency_order.models.mapper.OrderMapper;
import com.avalanche.high_concurrency_order.models.mapper.ProductMapper;
import com.avalanche.high_concurrency_order.models.mapper.StockLogMapper;
import com.avalanche.high_concurrency_order.core.exception.BusinessException;
import com.avalanche.high_concurrency_order.models.entity.Order;
import com.avalanche.high_concurrency_order.models.entity.Product;
import com.avalanche.high_concurrency_order.models.entity.StockLog;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderServiceImplement implements OrderService {

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;
    private final StockLogMapper stockLogMapper;
    private final StringRedisTemplate redisTemplate;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void placeOrder(Long productId, Integer quantity) {

        String redisKey = "product:stock:" + productId;
        Long remainStock = redisTemplate.opsForValue().decrement(redisKey, quantity);

        if (remainStock == null || remainStock < 0) {
            redisTemplate.opsForValue().increment(redisKey, quantity);
            throw new BusinessException(400, "Insufficient inventory");
        }

        Product product = productMapper.selectById(productId);

        if (product == null) {
            throw new BusinessException(400, "product does not exist, fail to place order");
        }

        int affectedRows = productMapper.deductStock(productId, quantity);
        if (affectedRows == 0) {
            redisTemplate.opsForValue().increment(redisKey, quantity);
            throw new BusinessException(400, "Insufficient inventory, fail to place order");
        }

        Order order = new Order();
        order.setUserId(1L);
        order.setProductId(productId);
        order.setQuantity(quantity);
        order.setAmount(product.getPrice().multiply(new java.math.BigDecimal(quantity)));
        order.setOrderSn(java.util.UUID.randomUUID().toString().replace("-", ""));
        orderMapper.insert(order);

        StockLog stockLog = new StockLog();
        stockLog.setProductId(productId);
        stockLog.setStockChange(-quantity);
        stockLog.setTransactionId(order.getOrderSn());
        stockLogMapper.insert(stockLog);

        log.info(">>> [SUCCESS] User successfully placed order! Order serial number: {}, Redis remaining inventory: {}",
                order.getOrderSn(), remainStock);
    }
}