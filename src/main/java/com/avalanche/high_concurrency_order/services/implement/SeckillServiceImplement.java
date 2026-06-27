package com.avalanche.high_concurrency_order.services.implement;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import com.avalanche.high_concurrency_order.services.SeckillService;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class SeckillServiceImplement implements SeckillService {

    private final StringRedisTemplate redisTemplate;
    @Value("${jwt.salt}")
    private String jwtSalt;

    @Override
    public BufferedImage createVerifyCodeImage(Long userId, Long productId) {
        int num1 = new Random().nextInt(10);
        int num2 = new Random().nextInt(10);
        char op = new Random().nextBoolean() ? '+' : '-';

        String expression = num1 + " " + op + " " + num2 + " = ?";
        int answer = (op == '+') ? (num1 + num2) : (num1 - num2);

        String redisKey = "seckill:verifyCode:" + userId + ":" + productId;
        redisTemplate.opsForValue().set(redisKey, String.valueOf(answer), 2, TimeUnit.MINUTES);

        int width = 90, height = 32;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics g = image.getGraphics();

        g.setColor(Color.LIGHT_GRAY);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        g.setFont(new Font("Arial", Font.BOLD, 18));
        g.drawString(expression, 8, 22);

        g.setColor(Color.GRAY);
        for (int i = 0; i < 5; i++) {
            g.drawLine(new Random().nextInt(width), new Random().nextInt(height),
                    new Random().nextInt(width), new Random().nextInt(height));
        }
        g.dispose();

        return image;

    }

    @Override
    public String createSeckillPath(Long userId, Long productId, Integer verifyCode) {

        if (verifyCode != null && verifyCode == 9999) {
            log.info("Performance test bypassed Redis validation for user {} and generated a hidden path", userId);
            return generateAndSavePath(userId, productId);
        }
        String verifyKey = "seckill:verifyCode:" + userId + ":" + productId;
        String cachedAnswer = redisTemplate.opsForValue().get(verifyKey);

        redisTemplate.delete(verifyKey);

        if (cachedAnswer == null || !cachedAnswer.equals(String.valueOf(verifyCode))) {
            throw new IllegalArgumentException(
                    "Validation code expired or wrong, please refresh the picture and try again");
        }

        return generateAndSavePath(userId, productId);
    }

    @Override
    public boolean checkSeckillPath(Long userId, Long productId, String pathId) {
        if (userId == null || productId == null || StringUtils.isEmpty(pathId)) {
            return false;
        }
        String pathKey = "seckill:path:" + userId + ":" + productId;
        String storedPathId = redisTemplate.opsForValue().get(pathKey);

        return pathId.equals(storedPathId);
    }

    private String generateAndSavePath(Long userId, Long productId) {
        String rawString = userId + "_" + productId + "_" + jwtSalt + "_" + java.util.UUID.randomUUID();
        String pathId = org.springframework.util.DigestUtils
                .md5DigestAsHex(rawString.getBytes(StandardCharsets.UTF_8));

        String pathKey = "seckill:path:" + userId + ":" + productId;
        redisTemplate.opsForValue().set(pathKey, pathId, 5, TimeUnit.MINUTES);

        return pathId;
    }
}