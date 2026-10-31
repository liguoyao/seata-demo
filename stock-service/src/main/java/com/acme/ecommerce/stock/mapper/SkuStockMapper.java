package com.acme.ecommerce.stock.mapper;

import com.acme.ecommerce.stock.model.SkuStock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SkuStockMapper extends BaseMapper<SkuStock> {}
