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

    private Integer stockChange; // 異動數量，例如下單為 -1

    private String type; // 異動類型，例如 "ORDER" 或 "REFUND"

    private String transactionId; // 對應的訂單編號 order_sn

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}