package com.acme.ecommerce.common.mapper;

import com.acme.ecommerce.common.model.InboxEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InboxEventMapper extends BaseMapper<InboxEvent> {}
