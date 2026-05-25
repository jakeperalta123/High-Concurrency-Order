package com.avalanche.high_concurrency_order.models.mapper;

import com.avalanche.high_concurrency_order.models.entity.Product;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * Atomic deduction of Inventory
     * 
     * @param productId
     * @param quantity  quantity to be deducted
     * @return affected rows，0 means inventory insufficient or product does not
     *         exist
     */
    @Update("UPDATE products SET stock = stock - #{quantity}, updated_at = NOW() " +
            "WHERE id = #{productId} AND stock >= #{quantity}")
    int deductStock(@Param("productId") Long productId, @Param("quantity") Integer quantity);
}