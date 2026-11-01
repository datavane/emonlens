package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetHealthSnapshotEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.DatasetRevisionEntity;
import io.emonlens.server.storage.entity.DatasetRevisionItemEntity;
import io.emonlens.server.storage.mapper.DatasetHealthSnapshotMapper;
import io.emonlens.server.storage.mapper.DatasetItemMapper;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.DatasetRevisionItemMapper;
import io.emonlens.server.storage.mapper.DatasetRevisionMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据集蓝图 + 保鲜/漂移。蓝图是数据集的最低风险、切分、用途配比；
 * 漂移以最近一次冻结 revision 为基线，当前可变样本集合的任何增删改都显式暴露。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatasetHealthService {

    private static final int MAX_STALE_DAYS_DEFAULT = 90;
    private static final int MAX_ITEMS_LIMIT = 100000;

    private final DatasetMapper datasetMapper;
    private final DatasetItemMapper datasetItemMapper;
    private final DatasetRevisionMapper datasetRevisionMapper;
    private final DatasetRevisionItemMapper datasetRevisionItemMapper;
    private final DatasetHealthSnapshotMapper datasetHealthSnapshotMapper;
    private final DomainPackService domainPackService;
    private final ObjectMapper objectMapper;

    public DatasetEntity requireDataset(String workspaceId, String datasetId) {
        DatasetEntity dataset = datasetMapper.selectById(datasetId);
        if (dataset == null || !Objects.equals(dataset.getWorkspaceId(), workspaceId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset not found");
        }
        return dataset;
    }

    public Map<String, Object> health(String workspaceId, String datasetId) {
        DatasetEntity dataset = requireDataset(workspaceId, datasetId);
        Map<String, Object> blueprint = parseMap(dataset.getBlueprintJson());
        String blueprintSource = blueprint == null || blueprint.isEmpty() ? "NONE" : "DATASET";
        String domainPackVersion = null;
        String domainPackName = null;
        String domainPackStatus = null;
        List<String> packWarnings = new ArrayList<>();
        Set<String> packRiskKeys = new java.util.HashSet<>();
        List<Map<String, Object>> packRiskTaxonomy = new ArrayList<>();
        if (dataset.getDomainPackId() != null) {
            try {
                Map<String, Object> pack = domainPackService.detail(workspaceId, dataset.getDomainPackId());
                domainPackVersion = asString(pack.get("version"));
                domainPackName = asString(pack.get("name"));
                domainPackStatus = asString(pack.get("status"));
                Map<String, Object> manifest = asMap(pack.get("manifest"));
                Object riskTaxonomy = manifest == null ? null : manifest.get("riskTaxonomy");
                if (riskTaxonomy instanceof List) {
                    for (Object row : (List<?>) riskTaxonomy) {
                        if (row instanceof Map && ((Map<?, ?>) row).get("key") != null) {
                            Map<?, ?> riskRow = (Map<?, ?>) row;
                            packRiskKeys.add(String.valueOf(riskRow.get("key")).toLowerCase());
                            Map<String, Object> normalized = new LinkedHashMap<>();
                            normalized.put("key", String.valueOf(riskRow.get("key")));
                            normalized.put("label", riskRow.get("label") == null
                                    ? String.valueOf(riskRow.get("key")) : String.valueOf(riskRow.get("label")));
                            packRiskTaxonomy.add(normalized);
                        }
                    }
                }
                Map<String, Object> packBlueprint = asMap(manifest == null ? null : manifest.get("datasetBlueprint"));
                if ((blueprint == null || blueprint.isEmpty())
                        && packBlueprint != null && !packBlueprint.isEmpty()) {
                    blueprint = packBlueprint;
                    blueprintSource = "DOMAIN_PACK";
                }
                if ("DEPRECATED".equals(domainPackStatus)) {
                    packWarnings.add("绑定的领域包已弃用：" + domainPackVersion + "；仍按冻结内容生效，但不能新绑定");
                }
            } catch (RuntimeException e) {
                packWarnings.add("绑定的领域包不可用：" + e.getMessage());
            }
        }
        boolean configured = blueprint != null && !blueprint.isEmpty();
        List<DatasetItemEntity> allItems = datasetItemMapper.selectList(
                Wrappers.<DatasetItemEntity>lambdaQuery()
                        .eq(DatasetItemEntity::getDatasetId, datasetId)
                        .orderByAsc(DatasetItemEntity::getCreatedAt));
        List<DatasetItemEntity> items = allItems.stream()
                .filter(item -> item.getStatus() == null || "ACTIVE".equals(item.getStatus()))
                .collect(java.util.stream.Collectors.toList());
        int invalidItemCount = allItems.size() - items.size();
        int maxItemAgeDays = configured ? intValue(blueprint.get("maxItemAgeDays"), 0) : 0;
        boolean autoQuarantineStale = configured
                && Boolean.TRUE.equals(blueprint.get("autoQuarantineStale"));
        List<Map<String, Object>> staleItemPreview = staleItems(items, maxItemAgeDays);
        List<String> unknownRiskLevels = new ArrayList<>();
        if (!packRiskKeys.isEmpty()) {
            for (DatasetItemEntity item : items) {
                String level = item.getRiskLevel();
                if (level != null && !level.isBlank() && !"UNASSIGNED".equalsIgnoreCase(level)
                        && !packRiskKeys.contains(level.toLowerCase())
                        && !unknownRiskLevels.contains(level)) {
                    unknownRiskLevels.add(level);
                }
            }
        }
        Map<String, Integer> risk = new LinkedHashMap<>();
        Map<String, Integer> split = new LinkedHashMap<>();
        Map<String, Integer> purpose = new LinkedHashMap<>();
        for (DatasetItemEntity item : items) {
            increment(risk, blank(item.getRiskLevel()) ? "UNASSIGNED" : item.getRiskLevel());
            increment(split, blank(item.getSplit()) ? "UNASSIGNED" : item.getSplit());
            Set<String> tags = tags(item.getPurposeTags());
            if (tags.isEmpty()) {
                increment(purpose, "UNASSIGNED");
            } else {
                tags.forEach(t -> increment(purpose, t));
            }
        }

        DatasetRevisionEntity latest = datasetRevisionMapper.selectOne(
                Wrappers.<DatasetRevisionEntity>lambdaQuery()
                        .eq(DatasetRevisionEntity::getDatasetId, datasetId)
                        .orderByDesc(DatasetRevisionEntity::getRevision)
                        .last("LIMIT 1"));

        List<Map<String, Object>> requirements = new ArrayList<>();
        if (configured) {
            int minItems = intValue(blueprint.get("minItems"), 0);
            addRequirement(requirements, "ITEM_COUNT", "items", minItems, items.size());
            Map<String, Object> riskMin = mapValue(blueprint.get("riskMin"));
            for (Map.Entry<String, Object> e : riskMin.entrySet()) {
                addRequirement(requirements, "RISK", e.getKey(),
                        intValue(e.getValue(), 0), countIgnoreCase(risk, e.getKey()));
            }
            Map<String, Object> splitMin = mapValue(blueprint.get("splitMin"));
            for (Map.Entry<String, Object> e : splitMin.entrySet()) {
                addRequirement(requirements, "SPLIT", e.getKey(),
                        intValue(e.getValue(), 0), countIgnoreCase(split, e.getKey()));
            }
            Map<String, Object> purposeMin = mapValue(blueprint.get("purposeMin"));
            for (Map.Entry<String, Object> e : purposeMin.entrySet()) {
                addRequirement(requirements, "PURPOSE", e.getKey(),
                        intValue(e.getValue(), 0), purpose.getOrDefault(e.getKey(), 0));
            }
        }
        long met = requirements.stream().filter(r -> Boolean.TRUE.equals(r.get("met"))).count();
        double coverage = requirements.isEmpty() ? 100.0
                : Math.round(met * 1000.0 / requirements.size()) / 10.0;

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime basis = latest != null ? latest.getCreatedAt()
                : (dataset.getUpdatedAt() != null ? dataset.getUpdatedAt() : dataset.getCreatedAt());
        long staleDays = basis == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(basis, now));
        int maxStaleDays = configured
                ? intValue(blueprint.get("maxStaleDays"), MAX_STALE_DAYS_DEFAULT)
                : MAX_STALE_DAYS_DEFAULT;
        boolean stale = basis != null && staleDays > maxStaleDays;

        Map<String, Object> drift = drift(datasetId, items, latest);
        List<String> warnings = new ArrayList<>(packWarnings);
        if (!configured) {
            warnings.add("未配置数据集蓝图：无法声明风险/切分/用途的最低覆盖");
        }
        for (Map<String, Object> requirement : requirements) {
            if (!Boolean.TRUE.equals(requirement.get("met"))) {
                warnings.add(requirement.get("label") + " 未达标（"
                        + requirement.get("actual") + "/" + requirement.get("required") + "）");
            }
        }
        if (latest == null) {
            warnings.add("尚未冻结数据集 revision：没有漂移基线");
        } else if (!"IN_SYNC".equals(drift.get("status"))) {
            warnings.add("当前样本相对最近 revision 已漂移：新增 " + drift.get("added")
                    + "，删除 " + drift.get("removed") + "，修改 " + drift.get("changed"));
        }
        if (!unknownRiskLevels.isEmpty()) {
            warnings.add("风险级别不在领域包 riskTaxonomy 中：" + String.join(",", unknownRiskLevels));
        }
        if (!staleItemPreview.isEmpty()) {
            warnings.add("有 " + staleItemPreview.size() + " 条样本超过保鲜上限 "
                    + maxItemAgeDays + " 天，可预检后软失效");
        }
        if (invalidItemCount > 0) {
            warnings.add("有 " + invalidItemCount + " 条样本已软失效，新 run 不再纳入；如需恢复请显式 restore");
        }
        if (stale) {
            warnings.add("数据集已 " + staleDays + " 天未刷新（上限 " + maxStaleDays + " 天）");
        }

        String status;
        if (!configured) {
            status = "NO_BLUEPRINT";
        } else if (coverage < 100.0 || stale || !staleItemPreview.isEmpty()
                || !"IN_SYNC".equals(drift.get("status"))) {
            status = "AT_RISK";
        } else {
            status = "READY";
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetId", datasetId);
        result.put("datasetKey", dataset.getDatasetKey());
        result.put("blueprintConfigured", configured);
        result.put("blueprintSource", blueprintSource);
        result.put("blueprint", configured ? blueprint : null);
        result.put("domainPackId", dataset.getDomainPackId());
        result.put("domainPackVersion", domainPackVersion);
        result.put("domainPackName", domainPackName);
        result.put("domainPackStatus", domainPackStatus);
        result.put("status", status);
        result.put("itemCount", items.size());
        result.put("totalItemCount", allItems.size());
        result.put("invalidItemCount", invalidItemCount);
        result.put("unknownRiskLevels", unknownRiskLevels);
        result.put("riskTaxonomy", packRiskTaxonomy);
        result.put("composition", composition(risk, split, purpose));
        result.put("requirements", requirements);
        result.put("requirementCoverage", coverage);
        result.put("stale", stale);
        result.put("staleDays", staleDays);
        result.put("maxStaleDays", maxStaleDays);
        result.put("maxItemAgeDays", maxItemAgeDays);
        result.put("autoQuarantineStale", autoQuarantineStale);
        result.put("staleItemCount", staleItemPreview.size());
        result.put("staleItems", staleItemPreview);
        result.put("freshnessBasis", basis);
        result.put("latestRevision", latest == null ? null : revisionSummary(latest));
        result.put("drift", drift);
        result.put("warnings", warnings);
        return result;
    }

    /** 绑定领域包后，新增样本的风险级别必须属于 pack 的 riskTaxonomy（大小写不敏感）。 */
    public void validateRiskLevel(String workspaceId, String datasetId, String riskLevel) {
        DatasetEntity dataset = requireDataset(workspaceId, datasetId);
        if (dataset.getDomainPackId() == null || dataset.getDomainPackId().isBlank()) {
            return;
        }
        String level = riskLevel == null ? "" : riskLevel.trim();
        if (level.isEmpty() || "UNASSIGNED".equalsIgnoreCase(level)) {
            return;
        }
        Map<String, Object> pack = domainPackService.detail(workspaceId, dataset.getDomainPackId());
        Object taxonomyRaw = pack.get("manifest") instanceof Map
                ? ((Map<String, Object>) pack.get("manifest")).get("riskTaxonomy") : null;
        Set<String> allowed = new java.util.HashSet<>();
        if (taxonomyRaw instanceof List) {
            for (Object row : (List<?>) taxonomyRaw) {
                if (row instanceof Map) {
                    Object key = ((Map<?, ?>) row).get("key");
                    if (key != null) {
                        allowed.add(String.valueOf(key).toLowerCase());
                    }
                }
            }
        }
        if (!allowed.isEmpty() && !allowed.contains(level.toLowerCase())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "风险级别不在领域包 riskTaxonomy 中: " + level
                            + "，允许: " + String.join(",", allowed));
        }
    }

    /** 预检/应用按样本年龄的软失效；只处理 ACTIVE，不物理删除，不自动执行。 */
    @Transactional
    public Map<String, Object> quarantineStaleItems(String workspaceId, String datasetId,
                                                    boolean apply) {
        DatasetEntity dataset = requireDataset(workspaceId, datasetId);
        Map<String, Object> blueprint = parseMap(dataset.getBlueprintJson());
        int maxItemAgeDays = blueprint == null ? 0 : intValue(blueprint.get("maxItemAgeDays"), 0);
        if (maxItemAgeDays <= 0) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "未配置 maxItemAgeDays，无法按样本年龄软失效");
        }
        List<DatasetItemEntity> active = datasetItemMapper.selectList(
                Wrappers.<DatasetItemEntity>lambdaQuery()
                        .eq(DatasetItemEntity::getDatasetId, datasetId)
                        .and(w -> w.eq(DatasetItemEntity::getStatus, "ACTIVE")
                                .or().isNull(DatasetItemEntity::getStatus)));
        List<Map<String, Object>> stale = staleItems(active, maxItemAgeDays);
        if (apply && !stale.isEmpty()) {
            List<String> ids = new ArrayList<>();
            for (Map<String, Object> row : stale) {
                ids.add(String.valueOf(row.get("itemId")));
            }
            datasetItemMapper.update(null, Wrappers.<DatasetItemEntity>lambdaUpdate()
                    .in(DatasetItemEntity::getId, ids)
                    .set(DatasetItemEntity::getStatus, "QUARANTINED")
                    .set(DatasetItemEntity::getInvalidReason, "STALE_AGE_" + maxItemAgeDays + "D")
                    .set(DatasetItemEntity::getInvalidatedAt, LocalDateTime.now()));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("datasetId", datasetId);
        result.put("maxItemAgeDays", maxItemAgeDays);
        result.put("apply", apply);
        result.put("staleCount", stale.size());
        result.put("appliedCount", apply ? stale.size() : 0);
        result.put("items", stale);
        return result;
    }

    /** 保鲜任务专用：只对显式开启 autoQuarantineStale 且配置 maxItemAgeDays 的数据集执行软失效。 */
    public int autoQuarantineStaleAll() {
        List<DatasetEntity> datasets = datasetMapper.selectList(
                Wrappers.<DatasetEntity>lambdaQuery()
                        .isNotNull(DatasetEntity::getBlueprintJson)
                        .ne(DatasetEntity::getBlueprintJson, ""));
        int total = 0;
        for (DatasetEntity dataset : datasets) {
            Map<String, Object> blueprint = parseMap(dataset.getBlueprintJson());
            if (blueprint == null || !Boolean.TRUE.equals(blueprint.get("autoQuarantineStale"))
                    || intValue(blueprint.get("maxItemAgeDays"), 0) <= 0) {
                continue;
            }
            try {
                Map<String, Object> result = quarantineStaleItems(
                        dataset.getWorkspaceId(), dataset.getId(), true);
                total += intValue(result.get("appliedCount"), 0);
            } catch (RuntimeException e) {
                log.warn("dataset stale auto quarantine failed, dataset={}, reason={}",
                        dataset.getId(), e.getMessage());
            }
        }
        log.info("dataset stale auto quarantine finished, applied={}", total);
        return total;
    }

    private List<Map<String, Object>> staleItems(List<DatasetItemEntity> items, int maxAgeDays) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (maxAgeDays <= 0) {
            return result;
        }
        LocalDateTime now = LocalDateTime.now();
        for (DatasetItemEntity item : items) {
            LocalDateTime createdAt = item.getCreatedAt();
            if (createdAt == null) {
                continue;
            }
            long ageDays = Math.max(0, ChronoUnit.DAYS.between(createdAt, now));
            if (ageDays > maxAgeDays) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("itemId", item.getId());
                row.put("input", item.getInput() == null ? null
                        : (item.getInput().length() > 120
                        ? item.getInput().substring(0, 120) + "…" : item.getInput()));
                row.put("createdAt", createdAt);
                row.put("ageDays", ageDays);
                result.add(row);
                if (result.size() >= 100) {
                    break;
                }
            }
        }
        return result;
    }

    @Transactional
    public Map<String, Object> upgradeDomainPack(String workspaceId, String datasetId,
                                                 String domainPackId) {
        DatasetEntity dataset = requireDataset(workspaceId, datasetId);
        if (dataset.getDomainPackId() == null || dataset.getDomainPackId().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "数据集尚未绑定领域包");
        }
        String targetId = domainPackId == null ? "" : domainPackId.trim();
        if (targetId.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "domainPackId 不能为空");
        }
        Map<String, Object> current = domainPackService.detail(workspaceId, dataset.getDomainPackId());
        Map<String, Object> target = domainPackService.detail(workspaceId, targetId);
        if (!asString(current.get("packKey")).equals(asString(target.get("packKey")))) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "只能升级同一 packKey 的领域包版本");
        }
        if (!DomainPackService.STATUS_PUBLISHED.equals(asString(target.get("status")))) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "只能升级到 PUBLISHED 版本");
        }
        if (compareVersions(asString(target.get("version")), asString(current.get("version"))) <= 0) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "目标版本必须高于当前版本: " + current.get("version"));
        }
        datasetMapper.update(null, Wrappers.<DatasetEntity>lambdaUpdate()
                .eq(DatasetEntity::getId, dataset.getId())
                .eq(DatasetEntity::getWorkspaceId, workspaceId)
                .set(DatasetEntity::getDomainPackId, targetId)
                .set(DatasetEntity::getUpdatedAt, LocalDateTime.now()));
        return health(workspaceId, datasetId);
    }

    private int compareVersions(String left, String right) {
        String[] a = left == null ? new String[0] : left.split("\\.");
        String[] b = right == null ? new String[0] : right.split("\\.");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length ? Integer.parseInt(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    @Transactional
    public Map<String, Object> bindDomainPack(String workspaceId, String datasetId, String domainPackId) {
        DatasetEntity dataset = requireDataset(workspaceId, datasetId);
        String packId = domainPackId == null || domainPackId.isBlank() ? null : domainPackId.trim();
        if (packId != null) {
            Map<String, Object> pack = domainPackService.detail(workspaceId, packId);
            if (!DomainPackService.STATUS_PUBLISHED.equals(asString(pack.get("status")))) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "只能绑定 PUBLISHED 领域包版本");
            }
        }
        datasetMapper.update(null, Wrappers.<DatasetEntity>lambdaUpdate()
                .eq(DatasetEntity::getId, dataset.getId())
                .eq(DatasetEntity::getWorkspaceId, workspaceId)
                .set(DatasetEntity::getDomainPackId, packId)
                .set(DatasetEntity::getUpdatedAt, LocalDateTime.now()));
        return health(workspaceId, datasetId);
    }

    /**
     * 周期扫描所有已配置蓝图的数据集并落快照。单个数据集失败不影响其他数据集；
     * 这里不做自动删除/自动改样本，只保留可追踪的保鲜证据。
     */
    public int scanAll() {
        List<DatasetEntity> datasets = datasetMapper.selectList(
                Wrappers.<DatasetEntity>lambdaQuery()
                        .isNotNull(DatasetEntity::getBlueprintJson)
                        .ne(DatasetEntity::getBlueprintJson, ""));
        int written = 0;
        for (DatasetEntity dataset : datasets) {
            try {
                Map<String, Object> result = health(dataset.getWorkspaceId(), dataset.getId());
                DatasetHealthSnapshotEntity snapshot = new DatasetHealthSnapshotEntity();
                snapshot.setWorkspaceId(dataset.getWorkspaceId());
                snapshot.setDatasetId(dataset.getId());
                snapshot.setStatus(Objects.toString(result.get("status"), null));
                snapshot.setItemCount(intValue(result.get("itemCount"), 0));
                snapshot.setRequirementCoverage(doubleValue(result.get("requirementCoverage")));
                snapshot.setStale(Boolean.TRUE.equals(result.get("stale")));
                snapshot.setStaleDays(longValue(result.get("staleDays")));
                Object latest = result.get("latestRevision");
                if (latest instanceof Map) {
                    snapshot.setLatestRevision(intValue(((Map<?, ?>) latest).get("revision"), 0));
                }
                snapshot.setHealthJson(toJson(result));
                snapshot.setCreatedAt(LocalDateTime.now());
                datasetHealthSnapshotMapper.insert(snapshot);
                written++;
            } catch (RuntimeException e) {
                log.warn("dataset health scan failed, dataset={}, reason={}",
                        dataset.getId(), e.getMessage());
            }
        }
        log.info("dataset health scan finished, datasets={}, snapshots={}", datasets.size(), written);
        return written;
    }

    public List<Map<String, Object>> history(String workspaceId, String datasetId, Integer limit) {
        requireDataset(workspaceId, datasetId);
        int size = limit == null ? 30 : Math.max(1, Math.min(limit, 100));
        List<DatasetHealthSnapshotEntity> rows = datasetHealthSnapshotMapper.selectList(
                Wrappers.<DatasetHealthSnapshotEntity>lambdaQuery()
                        .eq(DatasetHealthSnapshotEntity::getWorkspaceId, workspaceId)
                        .eq(DatasetHealthSnapshotEntity::getDatasetId, datasetId)
                        .orderByDesc(DatasetHealthSnapshotEntity::getCreatedAt)
                        .last("LIMIT " + size));
        List<Map<String, Object>> result = new ArrayList<>();
        for (DatasetHealthSnapshotEntity row : rows) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", row.getId());
            map.put("status", row.getStatus());
            map.put("itemCount", row.getItemCount());
            map.put("requirementCoverage", row.getRequirementCoverage());
            map.put("stale", row.getStale());
            map.put("staleDays", row.getStaleDays());
            map.put("latestRevision", row.getLatestRevision());
            map.put("createdAt", row.getCreatedAt());
            Map<String, Object> raw = parseMap(row.getHealthJson());
            map.put("warnings", raw == null ? List.of() : raw.getOrDefault("warnings", List.of()));
            result.add(map);
        }
        return result;
    }

    @Transactional
    public Map<String, Object> saveBlueprint(String workspaceId, String datasetId,
                                             Map<String, Object> body) {
        DatasetEntity dataset = requireDataset(workspaceId, datasetId);
        String json = null;
        if (body != null && !body.isEmpty()) {
            json = toJson(normalizeBlueprint(body));
        }
        // updateById 默认忽略 null，清除蓝图必须显式 set null。
        datasetMapper.update(null, Wrappers.<DatasetEntity>lambdaUpdate()
                .eq(DatasetEntity::getId, datasetId)
                .eq(DatasetEntity::getWorkspaceId, workspaceId)
                .set(DatasetEntity::getBlueprintJson, json)
                .set(DatasetEntity::getUpdatedAt, LocalDateTime.now()));
        return health(workspaceId, datasetId);
    }

    private Map<String, Object> normalizeBlueprint(Map<String, Object> body) {
        Set<String> allowed = Set.of("minItems", "riskMin", "splitMin", "purposeMin",
                "maxStaleDays", "maxItemAgeDays", "autoQuarantineStale");
        for (String key : body.keySet()) {
            if (!allowed.contains(key)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "未知蓝图字段: " + key);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        int minItems = boundedInt(body.get("minItems"), 0, "minItems");
        int maxStaleDays = boundedInt(body.get("maxStaleDays"), MAX_STALE_DAYS_DEFAULT,
                "maxStaleDays");
        result.put("minItems", minItems);
        result.put("riskMin", normalizeMinMap(body.get("riskMin"), "riskMin"));
        result.put("splitMin", normalizeMinMap(body.get("splitMin"), "splitMin"));
        result.put("purposeMin", normalizeMinMap(body.get("purposeMin"), "purposeMin"));
        result.put("maxStaleDays", maxStaleDays);
        result.put("maxItemAgeDays", boundedInt(body.get("maxItemAgeDays"), 0,
                "maxItemAgeDays"));
        result.put("autoQuarantineStale", Boolean.TRUE.equals(body.get("autoQuarantineStale")));
        return result;
    }

    private Map<String, Integer> normalizeMinMap(Object raw, String field) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 必须是对象");
        }
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            String key = String.valueOf(entry.getKey()).trim();
            if (key.isEmpty()) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 的键不能为空");
            }
            result.put(key, boundedInt(entry.getValue(), 0, field + "." + key));
        }
        return result;
    }

    private int boundedInt(Object raw, int defaultValue, String field) {
        if (raw == null) {
            return defaultValue;
        }
        int value;
        try {
            value = raw instanceof Number ? ((Number) raw).intValue()
                    : Integer.parseInt(String.valueOf(raw).trim());
        } catch (RuntimeException e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, field + " 必须是整数");
        }
        if (value < 0 || value > MAX_ITEMS_LIMIT) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    field + " 必须在 0.." + MAX_ITEMS_LIMIT + " 之间");
        }
        return value;
    }

    private int countIgnoreCase(Map<String, Integer> counts, String key) {
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue() == null ? 0 : entry.getValue();
            }
        }
        return 0;
    }

    private void addRequirement(List<Map<String, Object>> requirements, String type, String key,
                                int required, int actual) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", type);
        row.put("key", key);
        row.put("label", type + ":" + key);
        row.put("required", required);
        row.put("actual", actual);
        row.put("met", actual >= required);
        requirements.add(row);
    }

    private Map<String, Object> drift(String datasetId, List<DatasetItemEntity> current,
                                      DatasetRevisionEntity latest) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (latest == null) {
            result.put("status", "NO_BASELINE");
            result.put("added", 0);
            result.put("removed", 0);
            result.put("changed", 0);
            result.put("addedItems", List.of());
            result.put("removedItems", List.of());
            result.put("changedItems", List.of());
            return result;
        }
        Map<String, DatasetRevisionItemEntity> frozen = new LinkedHashMap<>();
        for (DatasetRevisionItemEntity item : datasetRevisionItemMapper.selectList(
                Wrappers.<DatasetRevisionItemEntity>lambdaQuery()
                        .eq(DatasetRevisionItemEntity::getRevisionId, latest.getId()))) {
            frozen.put(item.getItemKey(), item);
        }
        Map<String, DatasetItemEntity> live = new LinkedHashMap<>();
        for (DatasetItemEntity item : current) {
            live.put(item.getId(), item);
        }
        List<String> added = new ArrayList<>();
        for (String id : live.keySet()) {
            if (!frozen.containsKey(id)) {
                added.add(id);
            }
        }
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, DatasetRevisionItemEntity> entry : frozen.entrySet()) {
            DatasetItemEntity item = live.get(entry.getKey());
            if (item == null) {
                removed.add(entry.getKey());
                continue;
            }
            DatasetRevisionItemEntity old = entry.getValue();
            if (!Objects.equals(old.getInput(), item.getInput())
                    || !Objects.equals(old.getExpectedOutput(), item.getExpectedOutput())
                    || !Objects.equals(old.getPurposeTags(), item.getPurposeTags())
                    || !Objects.equals(old.getRiskLevel(), item.getRiskLevel())
                    || !Objects.equals(old.getSplit(), item.getSplit())
                    || !Objects.equals(old.getFailureMode(), item.getFailureMode())
                    || !Objects.equals(old.getMetadataJson(), item.getMetadataJson())) {
                changed.add(entry.getKey());
            }
        }
        boolean drifted = !added.isEmpty() || !removed.isEmpty() || !changed.isEmpty();
        result.put("status", drifted ? "DRIFTED" : "IN_SYNC");
        result.put("added", added.size());
        result.put("removed", removed.size());
        result.put("changed", changed.size());
        result.put("addedItems", head(added));
        result.put("removedItems", head(removed));
        result.put("changedItems", head(changed));
        return result;
    }

    private List<String> head(List<String> values) {
        return values.size() <= 5 ? values : new ArrayList<>(values.subList(0, 5));
    }

    private Map<String, Object> revisionSummary(DatasetRevisionEntity revision) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", revision.getId());
        map.put("revision", revision.getRevision());
        map.put("label", revision.getLabel());
        map.put("itemCount", revision.getItemCount());
        map.put("manifestHash", revision.getManifestHash());
        map.put("createdAt", revision.getCreatedAt());
        return map;
    }

    private Map<String, Object> composition(Map<String, Integer> risk,
                                            Map<String, Integer> split,
                                            Map<String, Integer> purpose) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("risk", risk);
        map.put("split", split);
        map.put("purpose", purpose);
        return map;
    }

    private Set<String> tags(String raw) {
        Set<String> result = new LinkedHashSet<>();
        if (raw == null) {
            return result;
        }
        for (String part : raw.split(",")) {
            String tag = part.trim();
            if (!tag.isEmpty()) {
                result.add(tag);
            }
        }
        return result;
    }

    private void increment(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private Map<String, Object> mapValue(Object raw) {
        if (!(raw instanceof Map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

    private int intValue(Object raw, int defaultValue) {
        if (raw instanceof Number) {
            return ((Number) raw).intValue();
        }
        if (raw == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(String.valueOf(raw));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Double doubleValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    private Long longValue(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "蓝图无法序列化");
        }
    }
}
