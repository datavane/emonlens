package io.emonlens.server.api;

import java.io.IOException;
import java.util.List;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.ApiResult;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.ingest.TrustContext;
import io.emonlens.server.storage.entity.IngestKeyEntity;
import io.emonlens.server.storage.mapper.IngestKeyMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ingest 通道鉴权：X-Trust-Key -> sha256 -> et_ingest_key（ACTIVE）。
 * 通过后把 TrustContext 放入 request attribute，供 controller 使用。
 * 由 IngestWebConfig 注册（仅拦 /api/v1/ingest/*），不用 @Component 以免全路径生效。
 */
@Slf4j
@RequiredArgsConstructor
public class IngestKeyFilter extends OncePerRequestFilter {

    public static final String TRUST_KEY_HEADER = "X-Trust-Key";
    public static final String TRUST_CONTEXT_ATTRIBUTE = "trustContext";

    private final IngestKeyMapper ingestKeyMapper;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(TRUST_KEY_HEADER);
        if (key == null || key.isEmpty()) {
            reject(response, "missing header " + TRUST_KEY_HEADER);
            return;
        }
        String keyHash = Sha256Utils.hash(key);
        List<IngestKeyEntity> keys = ingestKeyMapper.selectList(Wrappers.<IngestKeyEntity>lambdaQuery()
                .eq(IngestKeyEntity::getKeyHash, keyHash)
                .eq(IngestKeyEntity::getStatus, IngestKeyEntity.STATUS_ACTIVE));
        if (keys.isEmpty()) {
            reject(response, "invalid or revoked ingest key");
            return;
        }
        IngestKeyEntity keyEntity = keys.get(0);
        request.setAttribute(TRUST_CONTEXT_ATTRIBUTE,
                new TrustContext(keyEntity.getId(), keyEntity.getWorkspaceId()));
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResult.error(ErrorCode.UNAUTHORIZED, message)));
        log.debug("ingest auth rejected: {}", message);
    }
}
