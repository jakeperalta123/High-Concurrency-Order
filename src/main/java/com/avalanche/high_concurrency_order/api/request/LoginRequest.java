package com.avalanche.high_concurrency_order.api.request;

import lombok.Data;

@Data
public class LoginRequest {
    private String username;
    private String password;
}