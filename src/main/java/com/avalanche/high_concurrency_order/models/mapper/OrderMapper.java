package com.avalanche.high_concurrency_order.models.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.avalanche.high_concurrency_order.models.entity.Order;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

@Mapper
public interface OrderMapper extends BaseMapper<Order> {

}