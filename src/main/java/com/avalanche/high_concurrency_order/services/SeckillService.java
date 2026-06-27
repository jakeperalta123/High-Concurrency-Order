package com.avalanche.high_concurrency_order.services;

import java.awt.image.BufferedImage;

public interface SeckillService {

    String createSeckillPath(Long userId, Long productId, Integer verifyCode);

    boolean checkSeckillPath(Long userId, Long productId, String pathId);

    BufferedImage createVerifyCodeImage(Long userId, Long productId);

}