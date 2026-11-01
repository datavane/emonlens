package io.emonlens.server.engine.eval;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.storage.entity.BusinessSourceCredentialEntity;
import io.emonlens.server.storage.mapper.BusinessSourceCredentialMapper;

import lombok.RequiredArgsConstructor;

/** 外部证据源公钥管理。只存 RSA 公钥，私钥由数据源自己保管。 */
@Service
@RequiredArgsConstructor
public class BusinessSourceCredentialService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_REVOKED = "REVOKED";

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{3,63}");

    private final BusinessSourceCredentialMapper credentialMapper;

    @Transactional
    public Map<String, Object> create(String workspaceId, String keyId, String source,
                                      String name, String publicKeyBase64, String createdBy) {
        String normalizedKey = trimToNull(keyId);
        if (normalizedKey == null || !KEY_ID.matcher(normalizedKey).matches()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "keyId 必须匹配 [A-Za-z0-9][A-Za-z0-9_-]{3,63}");
        }
        String normalizedSource = required(source, "source", 100);
        String key = required(publicKeyBase64, "publicKey", 4096);
        PublicKey parsed = parseRsa(key);
        BusinessSourceCredentialEntity existing = credentialMapper.selectOne(
                Wrappers.<BusinessSourceCredentialEntity>lambdaQuery()
                        .eq(BusinessSourceCredentialEntity::getWorkspaceId, workspaceId)
                        .eq(BusinessSourceCredentialEntity::getKeyId, normalizedKey));
        if (existing != null) {
            throw new EmonLensException(ErrorCode.CONFLICT, "keyId 已存在: " + normalizedKey);
        }
        BusinessSourceCredentialEntity entity = new BusinessSourceCredentialEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setKeyId(normalizedKey);
        entity.setSource(normalizedSource);
        entity.setName(trimToNull(name));
        entity.setPublicKey(Base64.getEncoder().encodeToString(parsed.getEncoded()));
        entity.setStatus(STATUS_ACTIVE);
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(LocalDateTime.now());
        try {
            credentialMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            throw new EmonLensException(ErrorCode.CONFLICT, "keyId 已存在: " + normalizedKey);
        }
        return map(entity);
    }

    public List<Map<String, Object>> list(String workspaceId) {
        List<BusinessSourceCredentialEntity> rows = credentialMapper.selectList(
                Wrappers.<BusinessSourceCredentialEntity>lambdaQuery()
                        .eq(BusinessSourceCredentialEntity::getWorkspaceId, workspaceId)
                        .orderByDesc(BusinessSourceCredentialEntity::getCreatedAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (BusinessSourceCredentialEntity row : rows) {
            result.add(map(row));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> revoke(String workspaceId, String id) {
        BusinessSourceCredentialEntity entity = credentialMapper.selectById(id);
        if (entity == null || !Objects.equals(entity.getWorkspaceId(), workspaceId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "credential not found");
        }
        if (STATUS_REVOKED.equals(entity.getStatus())) {
            return map(entity);
        }
        entity.setStatus(STATUS_REVOKED);
        entity.setRevokedAt(LocalDateTime.now());
        credentialMapper.updateById(entity);
        return map(entity);
    }

    public BusinessSourceCredentialEntity active(String workspaceId, String keyId, String source) {
        BusinessSourceCredentialEntity entity = credentialMapper.selectOne(
                Wrappers.<BusinessSourceCredentialEntity>lambdaQuery()
                        .eq(BusinessSourceCredentialEntity::getWorkspaceId, workspaceId)
                        .eq(BusinessSourceCredentialEntity::getKeyId, keyId));
        if (entity == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "未知 sourceKeyId");
        }
        if (!STATUS_ACTIVE.equals(entity.getStatus())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "sourceKeyId 已撤销");
        }
        if (!Objects.equals(entity.getSource(), source)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "sourceKeyId 不属于该实验 source");
        }
        return entity;
    }

    public boolean verify(String publicKeyBase64, String signingString, String signatureBase64) {
        try {
            PublicKey publicKey = parseRsa(publicKeyBase64);
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(signingString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (EmonLensException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    private PublicKey parseRsa(String publicKeyBase64) {
        try {
            byte[] der = Base64.getDecoder().decode(publicKeyBase64);
            PublicKey key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
            if (!(key instanceof RSAPublicKey)
                    || ((RSAPublicKey) key).getModulus().bitLength() < 2048) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "publicKey 必须是至少 2048 位的 RSA 公钥");
            }
            return key;
        } catch (EmonLensException e) {
            throw e;
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "publicKey 不是合法的 RSA X.509 DER Base64");
        }
    }

    private Map<String, Object> map(BusinessSourceCredentialEntity e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", e.getId());
        map.put("keyId", e.getKeyId());
        map.put("source", e.getSource());
        map.put("name", e.getName());
        map.put("status", e.getStatus());
        map.put("createdBy", e.getCreatedBy());
        map.put("createdAt", e.getCreatedAt());
        map.put("revokedAt", e.getRevokedAt());
        return map;
    }

    private String required(String value, String field, int maxLength) {
        String text = trimToNull(value);
        if (text == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " is required");
        }
        if (text.length() > maxLength) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 超过最大长度 " + maxLength);
        }
        return text;
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
