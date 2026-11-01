package io.emonlens.server.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.engine.eval.BusinessEvidenceService;
import io.emonlens.server.audit.Audit;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/** 外部业务实验（D5）：实验身份和版本化证据，不把观测性报告写成因果结论。 */
@RestController
@RequestMapping("/api/v1/business-experiments")
@RequiredArgsConstructor
public class BusinessExperimentController {

    private final BusinessEvidenceService businessEvidenceService;
    private final io.emonlens.server.engine.eval.BusinessSourceCredentialService credentialService;

    @Data
    public static class CreateRequest {
        private String source;
        private String externalExperimentId;
        private String agentKey;
        private String runId;
        private String candidateFingerprint;
        private String controlFingerprint;
        private String target;
        private String designType;
        private Map<String, Object> design;
        private String primaryMetric;
        private List<Map<String, Object>> guardrails;
        private Integer minSampleSize;
        private Boolean requireSignature;
        private LocalDateTime startedAt;
        private LocalDateTime endedAt;
        private String createdBy;
    }

    @Data
    public static class EvidenceRequest {
        private String reportVersion;
        private Map<String, Object> design;
        private Map<String, Object> metrics;
        private Map<String, Object> qualityChecks;
        private LocalDateTime reportedAt;
        private String receivedBy;
        private String sourceKeyId;
        private String signature;
        private String signedAt;
        private String nonce;
    }

    @Data
    public static class CredentialCreateRequest {
        private String keyId;
        private String source;
        private String name;
        private String publicKey;
        private String createdBy;
    }

    @PostMapping("/credentials")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "business.credential.create", resourceType = "business_source_credential")
    public ApiResult<Map<String, Object>> createCredential(@RequestBody CredentialCreateRequest request) {
        request.setCreatedBy(CurrentContext.require().getUserId());
        return ApiResult.ok(credentialService.create(
                CurrentContext.require().getWorkspaceId(), request.getKeyId(),
                request.getSource(), request.getName(), request.getPublicKey(), request.getCreatedBy()));
    }

    @GetMapping("/credentials")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<List<Map<String, Object>>> credentials() {
        return ApiResult.ok(credentialService.list(CurrentContext.require().getWorkspaceId()));
    }

    @PostMapping("/credentials/{id}/revoke")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "business.credential.revoke", resourceType = "business_source_credential")
    public ApiResult<Map<String, Object>> revokeCredential(@PathVariable String id) {
        return ApiResult.ok(credentialService.revoke(
                CurrentContext.require().getWorkspaceId(), id));
    }

    @PostMapping
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "business.experiment.create", resourceType = "business_experiment")
    public ApiResult<Map<String, Object>> create(@RequestBody CreateRequest request) {
        request.setCreatedBy(CurrentContext.require().getUserId());
        return ApiResult.ok(businessEvidenceService.create(
                CurrentContext.require().getWorkspaceId(), request));
    }

    @GetMapping
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<List<Map<String, Object>>> list(
            @RequestParam(required = false) String runId) {
        return ApiResult.ok(businessEvidenceService.list(
                CurrentContext.require().getWorkspaceId(), runId));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        return ApiResult.ok(businessEvidenceService.detail(
                CurrentContext.require().getWorkspaceId(), id));
    }

    @PostMapping("/{id}/evidence")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "business.evidence.import", resourceType = "business_experiment")
    public ApiResult<Map<String, Object>> addEvidence(
            @PathVariable String id, @RequestBody EvidenceRequest request) {
        request.setReceivedBy(CurrentContext.require().getUserId());
        return ApiResult.ok(businessEvidenceService.addEvidence(
                CurrentContext.require().getWorkspaceId(), id, request));
    }
}
