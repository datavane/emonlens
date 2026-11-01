package io.emonlens.server.init;

import java.time.LocalDateTime;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.Sha256Utils;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.IngestKeyEntity;
import io.emonlens.server.storage.mapper.IngestKeyMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 启动时注册 emonlens.ingest.seed-key（若该 key 不存在）。
 * 使 compose/env 注入的生产数据面凭据开箱即用；已被禁用/吊销的同 hash key 不会重复注册。
 * 种子 key 绑定默认工作空间 ws-default；其余 key 由控制台签发（签发时明文仅显示一次）。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SeedKeyRunner implements ApplicationRunner {

    static final String SEED_WORKSPACE_ID = "ws-default";

    private final EmonLensProperties properties;
    private final IngestKeyMapper ingestKeyMapper;

    @Override
    public void run(ApplicationArguments args) {
        String seedKey = properties.getIngest().getSeedKey();
        if (seedKey == null || seedKey.isEmpty()) {
            return;
        }
        String hash = Sha256Utils.hash(seedKey);
        Long exists = ingestKeyMapper.selectCount(Wrappers.<IngestKeyEntity>lambdaQuery()
                .eq(IngestKeyEntity::getKeyHash, hash));
        if (exists != null && exists > 0) {
            return;
        }
        IngestKeyEntity entity = new IngestKeyEntity();
        entity.setKeyHash(hash);
        entity.setKeyMasked(mask(seedKey));
        entity.setWorkspaceId(SEED_WORKSPACE_ID);
        entity.setStatus(IngestKeyEntity.STATUS_ACTIVE);
        entity.setCreatedAt(LocalDateTime.now());
        ingestKeyMapper.insert(entity);
        log.info("seed ingest key registered (workspace={}, key={})", SEED_WORKSPACE_ID, entity.getKeyMasked());
    }

    private String mask(String plain) {
        String head = plain.substring(0, Math.min(2, plain.length()));
        String tail = plain.length() <= 4 ? "" : plain.substring(plain.length() - 4);
        return head + "****" + tail;
    }
}
