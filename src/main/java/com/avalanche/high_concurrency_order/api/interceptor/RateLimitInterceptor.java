package com.avalanche.high_concurrency_order.api.interceptor;

import java.util.Collections;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.avalanche.high_concurrency_order.api.response.Result;
import com.avalanche.high_concurrency_order.core.annotation.RateLimit;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate redisTemplate;
    private final DefaultRedisScript<Long> rateLimitScript;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public RateLimitInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = new DefaultRedisScript<>();
        this.rateLimitScript.setLocation(new ClassPathResource("lua/ratelimit.lua"));
        this.rateLimitScript.setResultType(Long.class);
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        HandlerMethod handlerMethod = (HandlerMethod) handler;
        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);

        if (rateLimit == null) {
            return true;
        }

        if (rateLimit != null) {
            String productIdStr = request.getParameter("productId");
            if (productIdStr == null) {
                return true;
            }

            String redisKey = "seckill:ratelimit:product:" + productIdStr;

            long capacity = rateLimit.capacity();
            long refillRate = rateLimit.refillRate();
            long nowSecond = System.currentTimeMillis() / 1000;

            List<String> keys = Collections.singletonList(redisKey);

            Long result = redisTemplate.execute(
                    rateLimitScript,
                    keys,
                    String.valueOf(capacity),
                    String.valueOf(refillRate),
                    String.valueOf(nowSecond));

            if (result == null || result == 0) {
                try {
                    log.warn("Traffic extremely heavy! Product: {} activated global rate limit, shutting down request",
                            productIdStr);
                    response.setStatus(429);
                    response.setContentType("application/json;charset=UTF-8");
                    Result<String> errorResult = Result.error(429, "Too many requests, please try again later");
                    String json = OBJECT_MAPPER.writeValueAsString(errorResult);
                    response.getWriter().write(json);
                } catch (Exception e) {
                    log.error("write error", e);
                }
                return false;
            }
        }
        return true;
    }
}