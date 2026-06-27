package com.avalanche.high_concurrency_order.models.mapper;

import com.avalanche.high_concurrency_order.models.entity.Product;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.math.BigDecimal;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
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

    @Select("SELECT id, name, price, stock FROM products WHERE id = #{id} FOR UPDATE")
    Product selectProductByIdForUpdate(@Param("id") Long id);

    @Update("UPDATE products SET price = #{price} WHERE id = #{productId}")
    void updateProductPriceById(@Param("price") BigDecimal price, @Param("productId") Long productId);
}