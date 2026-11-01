package io.emonlens.server.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.storage.entity.ModelPriceEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.ModelPriceMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.span.SpanEventStoreRegistry;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/** 模型价格定义（$/1M tokens），用于无显式成本上报时的成本估算。 */
@RestController
@RequestMapping("/api/v1/model-prices")
@RequiredArgsConstructor
public class ModelPriceController {

    private final ModelPriceMapper modelPriceMapper;
    private final SpanEventStoreRegistry spanEventStoreRegistry;
    private final TraceSummaryMapper traceSummaryMapper;
    private final io.emonlens.server.storage.trace.TraceAssemblyService traceAssemblyService;

    @Data
    public static class UpsertRequest {
        private String model;
        private BigDecimal inputPerMillion;
        private BigDecimal outputPerMillion;
        /** 缓存命中输入价；null=未配置（计价回退输入价 10%） */
        private BigDecimal cachedInputPerMillion;
        private String currency;
    }

    @GetMapping
    @RequiresPermission(Permissions.MODEL_QUERY)
    public ApiResult<List<ModelPriceEntity>> list() {
        return ApiResult.ok(modelPriceMapper.selectList(Wrappers.<ModelPriceEntity>lambdaQuery()
                .eq(ModelPriceEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                .orderByAsc(ModelPriceEntity::getModel)));
    }

    /** 价格 × 实际用量对照（近 30 天）：每模型 tokens/成本/调用数 + 未配置价格但有用量的模型清单 */
    @GetMapping("/usage")
    @RequiresPermission(Permissions.MODEL_QUERY)
    public ApiResult<Map<String, Object>> usage() {
        String ws = CurrentContext.require().getWorkspaceId();
        LocalDateTime since = LocalDateTime.now().minusDays(30);
        Map<String, Map<String, Object>> byModel = new TreeMap<>();

        // trace 级：调用数 / 错误 / 成本（含 model_name 为空的 unknown）
        List<TraceSummaryEntity> traces = traceSummaryMapper.selectList(
                Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getWorkspaceId, ws)
                        .ge(TraceSummaryEntity::getStartTime, since));
        for (TraceSummaryEntity t : traces) {
            String model = t.getModelName() == null || t.getModelName().isEmpty() ? "unknown" : t.getModelName();
            Map<String, Object> m = byModel.computeIfAbsent(model, k -> {
                Map<String, Object> init = new LinkedHashMap<>();
                init.put("model", k);
                init.put("traces", 0L);
                init.put("errors", 0L);
                init.put("costUsd", 0.0);
                return init;
            });
            m.put("traces", ((Number) m.get("traces")).longValue() + 1);
            if (t.getErrorCount() != null && t.getErrorCount() > 0) {
                m.put("errors", ((Number) m.get("errors")).longValue() + 1);
            }
            if (t.getTotalCostUsd() != null) {
                m.put("costUsd", Math.round((((Number) m.get("costUsd")).doubleValue()
                        + t.getTotalCostUsd().doubleValue()) * 1_000_000L) / 1_000_000.0);
            }
        }
        // span 级：token 用量（仅 llm span 有意义；span 表始终在 MySQL 侧聚合，轻量列）
        for (Map<String, Object> row : spanEventStoreRegistry.active().sumUsageByModel(ws, since)) {
            String model = String.valueOf(row.get("modelName"));
            Map<String, Object> m = byModel.computeIfAbsent(model, k -> {
                Map<String, Object> init = new LinkedHashMap<>();
                init.put("model", k);
                init.put("traces", 0L);
                init.put("errors", 0L);
                init.put("costUsd", 0.0);
                return init;
            });
            m.put("inputTokens", number(row.get("inputTokens")));
            m.put("outputTokens", number(row.get("outputTokens")));
            m.put("cachedInputTokens", number(row.get("cachedInputTokens")));
        }
        // 价格配置标记
        List<ModelPriceEntity> prices = modelPriceMapper.selectList(Wrappers.<ModelPriceEntity>lambdaQuery()
                .eq(ModelPriceEntity::getWorkspaceId, ws));
        Map<String, ModelPriceEntity> priceMap = prices.stream()
                .collect(Collectors.toMap(ModelPriceEntity::getModel, p -> p, (a, b) -> a));
        List<Map<String, Object>> rows = new ArrayList<>(byModel.values());
        List<Map<String, Object>> unpriced = new java.util.ArrayList<>();
        for (Map<String, Object> m : rows) {
            ModelPriceEntity p = priceMap.get(String.valueOf(m.get("model")));
            m.put("priced", p != null);
            if (p != null) {
                m.put("inputPerMillion", p.getInputPerMillion());
                m.put("outputPerMillion", p.getOutputPerMillion());
            } else if (((Number) m.get("traces")).longValue() > 0) {
                unpriced.add(m);
            }
        }
        rows.sort((a, b) -> Long.compare(((Number) b.get("traces")).longValue(),
                ((Number) a.get("traces")).longValue()));
        Map<String, Object> out = new HashMap<>();
        out.put("rows", rows);
        out.put("unpriced", unpriced);
        return ApiResult.ok(out);
    }

    @PostMapping
    @RequiresPermission(Permissions.MODEL_MANAGE)
    @Audit(action = "model-price.create", resourceType = "model-price")
    public ApiResult<ModelPriceEntity> create(@RequestBody UpsertRequest request) {
        String ws = CurrentContext.require().getWorkspaceId();
        if (request.getModel() == null || request.getModel().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "model is required");
        }
        ModelPriceEntity exists = modelPriceMapper.selectOne(Wrappers.<ModelPriceEntity>lambdaQuery()
                .eq(ModelPriceEntity::getWorkspaceId, ws)
                .eq(ModelPriceEntity::getModel, request.getModel()));
        if (exists != null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "model price already exists: " + request.getModel());
        }
        ModelPriceEntity entity = new ModelPriceEntity();
        entity.setWorkspaceId(ws);
        entity.setModel(request.getModel().trim());
        entity.setInputPerMillion(request.getInputPerMillion() == null ? BigDecimal.ZERO : request.getInputPerMillion());
        entity.setOutputPerMillion(request.getOutputPerMillion() == null ? BigDecimal.ZERO : request.getOutputPerMillion());
        entity.setCachedInputPerMillion(request.getCachedInputPerMillion());
        entity.setCurrency(request.getCurrency() == null ? "USD" : request.getCurrency());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        modelPriceMapper.insert(entity);
        // 价格后配不能只对新 span 生效：立即按新价重算该模型已有 llm span
        traceAssemblyService.reestimateCosts(ws, entity.getModel());
        return ApiResult.ok(entity);
    }

    @PutMapping("/{id}")
    @RequiresPermission(Permissions.MODEL_MANAGE)
    @Audit(action = "model-price.update", resourceType = "model-price")
    public ApiResult<ModelPriceEntity> update(@PathVariable String id, @RequestBody UpsertRequest request) {
        ModelPriceEntity entity = requireOwned(id);
        if (request.getInputPerMillion() != null) {
            entity.setInputPerMillion(request.getInputPerMillion());
        }
        if (request.getOutputPerMillion() != null) {
            entity.setOutputPerMillion(request.getOutputPerMillion());
        }
        if (request.getCachedInputPerMillion() != null) {
            entity.setCachedInputPerMillion(request.getCachedInputPerMillion());
        }
        if (request.getCurrency() != null) {
            entity.setCurrency(request.getCurrency());
        }
        entity.setUpdatedAt(LocalDateTime.now());
        modelPriceMapper.updateById(entity);
        // 改价即重算：旧成本仍按原价挂着，不重算则"改了价格数字不动"
        traceAssemblyService.reestimateCosts(entity.getWorkspaceId(), entity.getModel());
        return ApiResult.ok(entity);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permissions.MODEL_MANAGE)
    @Audit(action = "model-price.delete", resourceType = "model-price")
    public ApiResult<Void> delete(@PathVariable String id) {
        requireOwned(id);
        modelPriceMapper.deleteById(id);
        return ApiResult.ok(null);
    }

    private ModelPriceEntity requireOwned(String id) {
        ModelPriceEntity entity = modelPriceMapper.selectById(id);
        if (entity == null || !entity.getWorkspaceId().equals(CurrentContext.require().getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "model price not found");
        }
        return entity;
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }
}
