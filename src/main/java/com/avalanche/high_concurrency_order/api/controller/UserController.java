package com.avalanche.high_concurrency_order.api.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RestController;

import com.avalanche.high_concurrency_order.api.request.RegisterRequest;
import com.avalanche.high_concurrency_order.api.response.Result;
import com.avalanche.high_concurrency_order.services.UserService;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RestController
@RequestMapping("/api/users")
public class UserController {

    @Autowired
    private UserService userService;

    @PostMapping("/register")
    public Result<String> registerUser(@RequestBody RegisterRequest registerRequest) {
        userService.registerUser(registerRequest.getUsername(), registerRequest.getPassword(),
                registerRequest.getEmail());
        return Result.success("User created successfully!");
    }

}