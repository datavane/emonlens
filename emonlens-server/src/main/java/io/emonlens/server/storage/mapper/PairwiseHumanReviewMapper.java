package io.emonlens.server.storage.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import io.emonlens.server.storage.entity.PairwiseHumanReviewEntity;

@Mapper
public interface PairwiseHumanReviewMapper extends BaseMapper<PairwiseHumanReviewEntity> {
}
