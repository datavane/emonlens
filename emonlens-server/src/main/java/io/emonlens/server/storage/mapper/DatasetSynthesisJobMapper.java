package io.emonlens.server.storage.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.emonlens.server.storage.entity.DatasetSynthesisJobEntity;

@Mapper
public interface DatasetSynthesisJobMapper extends BaseMapper<DatasetSynthesisJobEntity> {
}
