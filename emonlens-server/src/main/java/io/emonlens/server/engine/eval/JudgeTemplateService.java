package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.storage.entity.JudgeTemplateEntity;
import io.emonlens.server.storage.mapper.JudgeTemplateMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Judge 模板解析与 workspace 自定义模板版本化。
 *
 * <p>解析优先级：显式 key/version → workspace 模板 → 平台内置 → 默认 holistic。
 * 模板内容不可变；修改只能创建新版本，旧 run 的 template hash 永远可追溯。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JudgeTemplateService {

    private static final String WORKSPACE_PREFIX = "workspace/";
    private static final String PLATFORM_PREFIX = "platform/";

    private final JudgeTemplateMapper judgeTemplateMapper;
    private final JudgeTemplateRegistry registry;

    /** 解析失败必须报错：不能悄悄回退到默认模板而改变判分语义。 */
    public JudgeTemplate resolve(String workspaceId, String key, Integer version) {
        if (key == null || key.isBlank()) {
            return requirePlatform(JudgeTemplateRegistry.HOLISTIC_KEY, version);
        }
        String trimmed = key.trim();
        if (trimmed.startsWith(PLATFORM_PREFIX)) {
            return requirePlatform(trimmed, version);
        }
        if (!trimmed.startsWith(WORKSPACE_PREFIX)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "judgeTemplateKey 必须以 platform/ 或 workspace/ 开头: " + trimmed);
        }
        List<JudgeTemplateEntity> rows = judgeTemplateMapper.selectList(
                Wrappers.<JudgeTemplateEntity>lambdaQuery()
                        .eq(JudgeTemplateEntity::getWorkspaceId, workspaceId)
                        .eq(JudgeTemplateEntity::getTemplateKey, trimmed)
                        // 显式版本允许已停用版本：旧评估器/旧 run 必须能按固定版本重放；
                        // 不指定版本时只选仍处于 PUBLISHED 的最新版本。
                        .eq(version == null, JudgeTemplateEntity::getStatus,
                                JudgeTemplateEntity.STATUS_PUBLISHED)
                        .eq(version != null, JudgeTemplateEntity::getVersion, version)
                        .orderByDesc(JudgeTemplateEntity::getVersion)
                        .last("LIMIT 1"));
        if (rows.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "judge template not found: " + trimmed + (version == null ? "" : "@" + version));
        }
        return toTemplate(rows.get(0));
    }

    private JudgeTemplate requirePlatform(String key, Integer version) {
        JudgeTemplate template = registry.resolve(key, version);
        if (template == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "platform judge template not found: " + key
                            + (version == null ? "" : "@" + version));
        }
        return template;
    }

    @Transactional
    public JudgeTemplateEntity create(String workspaceId, String key, String name,
                                      String systemPrompt, String userPrompt,
                                      String outputSchema, String createdBy) {
        if (key == null || !key.matches("workspace/[A-Za-z0-9._-]{1,80}")) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "workspace 模板 key 必须匹配 workspace/[A-Za-z0-9._-]{1,80}");
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "systemPrompt 必填");
        }
        JudgeTemplateEntity latest = judgeTemplateMapper.selectOne(
                Wrappers.<JudgeTemplateEntity>lambdaQuery()
                        .eq(JudgeTemplateEntity::getWorkspaceId, workspaceId)
                        .eq(JudgeTemplateEntity::getTemplateKey, key)
                        .orderByDesc(JudgeTemplateEntity::getVersion)
                        .last("LIMIT 1"));
        int nextVersion = latest == null || latest.getVersion() == null ? 1 : latest.getVersion() + 1;
        String fingerprint = fingerprint(systemPrompt, userPrompt, outputSchema);
        if (latest != null && JudgeTemplateEntity.STATUS_PUBLISHED.equals(latest.getStatus())
                && fingerprint.equals(latest.getFingerprint())) {
            return latest;
        }
        JudgeTemplateEntity entity = new JudgeTemplateEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setTemplateKey(key);
        entity.setVersion(nextVersion);
        entity.setName(name == null || name.isBlank() ? key : name.trim());
        entity.setSystemPrompt(systemPrompt);
        entity.setUserPrompt(userPrompt);
        entity.setOutputSchema(outputSchema);
        entity.setStatus(JudgeTemplateEntity.STATUS_PUBLISHED);
        entity.setFingerprint(fingerprint);
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(LocalDateTime.now());
        judgeTemplateMapper.insert(entity);
        return entity;
    }

    public List<Map<String, Object>> list(String workspaceId, String key) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JudgeTemplate template : registry.list()) {
            if (key == null || key.isBlank() || template.getKey().equals(key.trim())) {
                result.add(toMap(template));
            }
        }
        List<JudgeTemplateEntity> rows = judgeTemplateMapper.selectList(
                Wrappers.<JudgeTemplateEntity>lambdaQuery()
                        .eq(JudgeTemplateEntity::getWorkspaceId, workspaceId)
                        .eq(key != null && !key.isBlank(), JudgeTemplateEntity::getTemplateKey, key)
                        .orderByAsc(JudgeTemplateEntity::getTemplateKey)
                        .orderByDesc(JudgeTemplateEntity::getVersion));
        for (JudgeTemplateEntity row : rows) {
            result.add(toMap(row));
        }
        return result;
    }

    public JudgeTemplateEntity get(String workspaceId, String id) {
        JudgeTemplateEntity entity = judgeTemplateMapper.selectById(id);
        if (entity == null || !workspaceId.equals(entity.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "judge template not found");
        }
        return entity;
    }

    @Transactional
    public JudgeTemplateEntity deprecate(String workspaceId, String id) {
        JudgeTemplateEntity entity = get(workspaceId, id);
        entity.setStatus(JudgeTemplateEntity.STATUS_DEPRECATED);
        judgeTemplateMapper.updateById(entity);
        return entity;
    }

    public Map<String, Object> toMap(JudgeTemplate template) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", null);
        map.put("templateKey", template.getKey());
        map.put("version", template.getVersion());
        map.put("name", template.getKey());
        map.put("systemPrompt", template.getSystemPrompt());
        map.put("userPrompt", template.getUserPrompt());
        map.put("outputSchema", template.getOutputSchema());
        map.put("status", JudgeTemplateEntity.STATUS_PUBLISHED);
        map.put("fingerprint", template.getHash());
        map.put("source", JudgeTemplate.SOURCE_PLATFORM);
        return map;
    }

    public Map<String, Object> toMap(JudgeTemplateEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", entity.getId());
        map.put("templateKey", entity.getTemplateKey());
        map.put("version", entity.getVersion());
        map.put("name", entity.getName());
        map.put("systemPrompt", entity.getSystemPrompt());
        map.put("userPrompt", entity.getUserPrompt());
        map.put("outputSchema", entity.getOutputSchema());
        map.put("status", entity.getStatus());
        map.put("fingerprint", entity.getFingerprint());
        map.put("source", JudgeTemplate.SOURCE_WORKSPACE);
        map.put("createdBy", entity.getCreatedBy());
        map.put("createdAt", entity.getCreatedAt());
        return map;
    }

    private JudgeTemplate toTemplate(JudgeTemplateEntity entity) {
        return new JudgeTemplate(entity.getTemplateKey(), entity.getVersion(),
                entity.getSystemPrompt(), entity.getUserPrompt(), entity.getOutputSchema(),
                entity.getFingerprint(), JudgeTemplate.SOURCE_WORKSPACE);
    }

    private String fingerprint(String systemPrompt, String userPrompt, String outputSchema) {
        return Sha256Utils.hash((systemPrompt == null ? "" : systemPrompt) + "|"
                + (userPrompt == null ? "" : userPrompt) + "|"
                + (outputSchema == null ? "" : outputSchema));
    }
}
