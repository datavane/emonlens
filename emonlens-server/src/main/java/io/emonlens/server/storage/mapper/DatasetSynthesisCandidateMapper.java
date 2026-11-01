package io.emonlens.server.storage.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.emonlens.server.storage.entity.DatasetSynthesisCandidateEntity;

@Mapper
public interface DatasetSynthesisCandidateMapper extends BaseMapper<DatasetSynthesisCandidateEntity> {
}
