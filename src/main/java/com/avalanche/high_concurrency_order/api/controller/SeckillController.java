package com.avalanche.high_concurrency_order.api.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.avalanche.high_concurrency_order.api.producer.SeckillOrderProducer;
import com.avalanche.high_concurrency_order.api.response.Result;
import com.avalanche.high_concurrency_order.core.annotation.RateLimit;
import com.avalanche.high_concurrency_order.core.constant.RedisKeyConstants;
import com.avalanche.high_concurrency_order.core.security.SecurityUser;
import com.avalanche.high_concurrency_order.services.OrderService;
import com.avalanche.high_concurrency_order.services.SeckillService;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.io.OutputStream;

import javax.imageio.ImageIO;

import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;

@RestController
@Slf4j
@RequestMapping("/api/seckill")
public class SeckillController {

    private final SeckillService secKillService;
    private final OrderService orderService;
    private final SeckillOrderProducer seckillOrderProducer;
    private final RBloomFilter<Long> productIdBloomFilter;

    public SeckillController(SeckillService seckillService,
            SeckillOrderProducer seckillOrderProducer,
            OrderService orderService,
            RedissonClient redissonClient) {
        this.secKillService = seckillService;
        this.seckillOrderProducer = seckillOrderProducer;
        this.orderService = orderService;
        this.productIdBloomFilter = redissonClient.getBloomFilter(RedisKeyConstants.BLOOM_FILTER_NAME);
    }

    @GetMapping("/verifyCode")
    public void getSeckillVerifyCode(HttpServletResponse response, @RequestParam Long productId) {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        if ("anonymousUser".equals(principal) || principal == null) {
            log.error("❌ [Security Interception] Unauthorized concurrent request");
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        Long userId = ((SecurityUser) principal).getUserId();
        try {
            if (!productIdBloomFilter.contains(productId)) {
                log.warn(
                        "[BloomFilter interception - VerifyCode] detected made-up product id: {}, malicious request from user: {}",
                        productId, userId);
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }
            BufferedImage image = secKillService.createVerifyCodeImage(userId, productId);
            response.setContentType("image/png");
            OutputStream out = response.getOutputStream();
            ImageIO.write(image, "PNG", out);
            out.flush();
            out.close();
        } catch (Exception e) {
            log.error("error occured during generation of verify code: ", e);
        }
    }

    @GetMapping("/path")
    // @RateLimit(capacity = 5, refillRate = 1)
    public Result<String> getSeckillPath(@RequestParam Long productId, @RequestParam Integer verifyCode) {
        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();

        if ("anonymousUser".equals(principal) || principal == null) {
            log.error("❌ [Security Interception] Unauthorized concurrent request");
            return Result.error(401, "Unauthorized");
        }

        Long userId = ((SecurityUser) principal).getUserId();

        try {
            if (!productIdBloomFilter.contains(productId)) {
                log.warn(
                        "[BloomFilter interception - VerifyCode] detected made-up product id: {}, malicious request from user: {}",
                        productId, userId);
                return Result.error(400, "Illegal request, product does not exist");
            }
            String pathId = secKillService.createSeckillPath(userId, productId, verifyCode);
            return Result.success(pathId);
        } catch (IllegalArgumentException e) {
            return Result.error(400, e.getMessage());
        }
    }

    @PostMapping("/{pathId}/order")
    public Result<String> placeOrderV4(
            @PathVariable String pathId,
            @RequestParam Long productId,
            @RequestParam Integer quantity) {

        Object principal = SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = ((SecurityUser) principal).getUserId();

        if (!productIdBloomFilter.contains(productId)) {
            log.error(
                    "🛑 [BloomFilter interception - Order] Malicious or illegal product Id: {}, request terminated! User: {}",
                    productId, userId);
            return Result.error(400, "Request rejected, illegal product identity");
        }

        // boolean check = secKillService.checkSeckillPath(userId, productId, pathId);
        // if (!check) {
        // return Result.error(404, "illegal request, endpoint does not exist or
        // expired");
        // }

        Long remainStock = orderService.deductRedisStock(productId, quantity);
        if (remainStock < 0) {
            log.warn("[Seckill] Product: {} Insufficient Inventory, User: {} failed to place order", productId, userId);
            return Result.error(400, "Sorry, Insufficient inventory");
        }

        // String orderSn = "test-idempotency-sn-99999";
        String orderSn = java.util.UUID.randomUUID().toString().replace("-", "");
        seckillOrderProducer.sendSeckillMessage(userId, productId, quantity, remainStock, orderSn);
        return Result.success("Order request submitted, issuing ticket, please check status in order center later!");
    }

}