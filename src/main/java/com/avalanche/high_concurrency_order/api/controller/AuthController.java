package com.avalanche.high_concurrency_order.api.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.avalanche.high_concurrency_order.api.request.LoginRequest;
import com.avalanche.high_concurrency_order.api.response.LoginResponse;
import com.avalanche.high_concurrency_order.api.response.Result;
import com.avalanche.high_concurrency_order.core.security.SecurityUser;
import com.avalanche.high_concurrency_order.utils.JwtUtils;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthenticationManager authenticationManager;

    @Autowired
    private JwtUtils jwtUtils;

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody LoginRequest loginRequest) {

        UsernamePasswordAuthenticationToken authenticationToken = new UsernamePasswordAuthenticationToken(
                loginRequest.getUsername(), loginRequest.getPassword());

        Authentication authentication = authenticationManager.authenticate(authenticationToken);

        SecurityUser userDetails = (SecurityUser) authentication.getPrincipal();

        Long realUserId = userDetails.getUserId();

        String jwt = jwtUtils.generateToken(realUserId);

        SecurityContextHolder.getContext().setAuthentication(authentication);

        return Result.success(new LoginResponse(jwt, "Bearer"));
    }

}