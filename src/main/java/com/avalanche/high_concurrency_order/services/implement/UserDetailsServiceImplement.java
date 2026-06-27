package com.avalanche.high_concurrency_order.services.implement;

import java.util.ArrayList;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.avalanche.high_concurrency_order.core.security.SecurityUser;
import com.avalanche.high_concurrency_order.models.entity.User;
import com.avalanche.high_concurrency_order.models.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

@Service
public class UserDetailsServiceImplement implements UserDetailsService {

    @Autowired
    private UserMapper userMapper;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));

        if (user == null) {
            throw new UsernameNotFoundException("Unable to find user: " + username);
        }

        return new SecurityUser(
                user.getId(),
                user.getUsername(),
                user.getPasswordHash(),
                new ArrayList<>());
    }
}