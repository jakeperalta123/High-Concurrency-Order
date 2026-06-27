package com.avalanche.high_concurrency_order.models.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("stock_log")
public class StockLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long productId;

    private Integer stockChange; // Stock change amount, for example -1 for an order

    private String type; // Change type, for example "ORDER" or "REFUND"

    private String transactionId; // Corresponding order number, order_sn

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}