package io.emonlens.server.api;

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
import io.emonlens.server.audit.Audit;
import io.emonlens.server.engine.eval.DomainPackService;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/** 领域包：声明质量模型、风险分类、数据蓝图和门禁模板。版本不可变。 */
@RestController
@RequestMapping("/api/v1/domain-packs")
@RequiredArgsConstructor
public class DomainPackController {

    private final DomainPackService domainPackService;

    @Data
    public static class CreateRequest {
        private String packKey;
        private String version;
        private String name;
        private String description;
        private String compatibility;
        private Map<String, Object> manifest;
        private String createdBy;
    }

    @PostMapping
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "domain.pack.create", resourceType = "domain_pack")
    public ApiResult<Map<String, Object>> create(@RequestBody CreateRequest request) {
        request.setCreatedBy(CurrentContext.require().getUserId());
        return ApiResult.ok(domainPackService.create(
                CurrentContext.require().getWorkspaceId(), request));
    }

    @GetMapping
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(required = false) String packKey) {
        return ApiResult.ok(domainPackService.list(
                CurrentContext.require().getWorkspaceId(), packKey));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        return ApiResult.ok(domainPackService.detail(
                CurrentContext.require().getWorkspaceId(), id));
    }

    @GetMapping("/{packKey}/versions")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<List<Map<String, Object>>> versions(@PathVariable String packKey) {
        return ApiResult.ok(domainPackService.versions(
                CurrentContext.require().getWorkspaceId(), packKey));
    }

    @PostMapping("/{id}/deprecate")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "domain.pack.deprecate", resourceType = "domain_pack")
    public ApiResult<Map<String, Object>> deprecate(@PathVariable String id) {
        return ApiResult.ok(domainPackService.deprecate(
                CurrentContext.require().getWorkspaceId(), id));
    }
}
