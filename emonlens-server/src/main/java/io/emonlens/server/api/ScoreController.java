package io.emonlens.server.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.engine.score.TrustScoreService;
import io.emonlens.server.storage.entity.TrustScoreEntity;

import lombok.RequiredArgsConstructor;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;

@RestController
@RequestMapping("/api/v1/score")
@RequiresPermission(Permissions.TRUST_OVERVIEW_QUERY)
@RequiredArgsConstructor
public class ScoreController {

    private final TrustScoreService trustScoreService;
    private final ObjectMapper objectMapper;

    @GetMapping("/overview")
    public ApiResult<Map<String, TrustScoreEntity>> overview() {
        Map<String, TrustScoreEntity> byAgent = new java.util.HashMap<>();
        for (TrustScoreEntity snapshot : trustScoreService.latestPerAgent(CurrentContext.require().getWorkspaceId())) {
            byAgent.put(snapshot.getScopeId(), snapshot);
        }
        return ApiResult.ok(byAgent);
    }

    @GetMapping("/agents/{agentId}")
    public ApiResult<TrustScoreEntity> agent(@PathVariable String agentId,
                                             @RequestParam(defaultValue = "24h") String window) {
        TrustScoreEntity snapshot = trustScoreService.latest(CurrentContext.require().getWorkspaceId(), agentId, window);
        if (snapshot == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "no score snapshot for agent: " + agentId);
        }
        return ApiResult.ok(snapshot);
    }
}
