package com.avalanche.high_concurrency_order.services.implement;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.avalanche.high_concurrency_order.models.entity.User;
import com.avalanche.high_concurrency_order.models.mapper.UserMapper;
import com.avalanche.high_concurrency_order.services.UserService;

@Service
public class UserServiceImplement implements UserService {

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void registerUser(String username, String password, String email) {
        try {
            User user = new User();
            user.setUsername(username);
            user.setPasswordHash(passwordEncoder.encode(password));
            user.setEmail(email);
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new RuntimeException("username or email already in use, try another one!");
        } catch (Exception e) {
            throw new RuntimeException("register failed!");
        }

    }
}