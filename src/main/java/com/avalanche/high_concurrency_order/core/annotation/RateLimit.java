package com.avalanche.high_concurrency_order.core.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    String key() default "default_limit";

    long capacity() default 10;

    long refillRate() default 2;
}