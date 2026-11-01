package io.emonlens.server.engine.eval;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.emonlens.server.config.SysSettingService;

/**
 * 数据集运行的有效 LLM 凭据/配置内存快照。
 * 只在当前进程内有效：进程重启后不恢复；PENDING run 由新进程使用当时配置执行。
 * 快照绝不落库（config_snapshot_json 不含 apiKey）。
 */
@Component
public class DatasetRunCredentialStore {

    private final Map<String, SysSettingService.EvalLlm> byRun = new ConcurrentHashMap<>();
    private final ThreadLocal<SysSettingService.EvalLlm> current = new ThreadLocal<>();

    public void put(String runId, SysSettingService.EvalLlm config) {
        if (runId != null && config != null) {
            byRun.put(runId, config);
        }
    }

    public void bind(String runId) {
        current.set(runId == null ? null : byRun.get(runId));
    }

    public void clear() {
        current.remove();
    }

    public SysSettingService.EvalLlm current() {
        return current.get();
    }

    public void remove(String runId) {
        if (runId != null) {
            byRun.remove(runId);
        }
    }
}
