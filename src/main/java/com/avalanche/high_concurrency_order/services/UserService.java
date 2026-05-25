package com.avalanche.high_concurrency_order.services;

public interface UserService {

    void registerUser(String username, String password, String email);

}