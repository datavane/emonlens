package io.emonlens.server.storage.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.emonlens.server.storage.entity.EvalCacheEntity;

@Mapper
public interface EvalCacheMapper extends BaseMapper<EvalCacheEntity> {
}
