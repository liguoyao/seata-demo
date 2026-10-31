package com.acme.ecommerce.order.mapper;

import com.acme.ecommerce.order.model.OrderSaga;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface OrderSagaMapper extends BaseMapper<OrderSaga> {}
