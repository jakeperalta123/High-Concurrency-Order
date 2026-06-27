package com.avalanche.high_concurrency_order;

import com.avalanche.high_concurrency_order.models.entity.User;
import com.avalanche.high_concurrency_order.models.mapper.UserMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;

@SpringBootTest
@Slf4j
public class UserPreloadTest extends ServiceImpl<UserMapper, User> {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    public void preloadTwoThousandUsers() {
        log.info("Starting batch preload of 2000 seckill test accounts");

        String commonPasswordHash = passwordEncoder.encode("123456");

        List<User> userList = new ArrayList<>(2000);

        for (int i = 1; i <= 2000; i++) {
            User user = new User();
            user.setUsername("test_user_" + i);
            user.setPasswordHash(commonPasswordHash);
            user.setEmail("test_user_" + i + "@avalanche.com");

            userList.add(user);
        }

        long startTime = System.currentTimeMillis();

        boolean success = this.saveBatch(userList, 100);

        long endTime = System.currentTimeMillis();

        if (success) {
            log.info("Successfully inserted {} users", userList.size());
            log.info("Total elapsed time: {} ms", endTime - startTime);
        } else {
            log.error("Batch insert failed; please check the database connection or column validation");
        }
    }
}