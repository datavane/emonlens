package io.emonlens.server.auth;

import java.util.List;

/** 权限串常量，统一 workspace: 前缀。 */
public final class Permissions {

    public static final String TRUST_OVERVIEW_QUERY = "workspace:trust:overview:query";
    public static final String TRACE_QUERY = "workspace:trust:trace:query";
    public static final String TRACE_CONTENT = "workspace:trust:trace:content";
    public static final String FINDING_QUERY = "workspace:trust:finding:query";
    public static final String INCIDENT_QUERY = "workspace:trust:incident:query";
    public static final String INCIDENT_MANAGE = "workspace:trust:incident:manage";
    public static final String MONITOR_QUERY = "workspace:trust:monitor:query";
    public static final String MONITOR_MANAGE = "workspace:trust:monitor:manage";
    public static final String EVALUATOR_QUERY = "workspace:trust:evaluator:query";
    public static final String EVALUATOR_MANAGE = "workspace:trust:evaluator:manage";
    public static final String EVALUATION_QUERY = "workspace:trust:evaluation:query";
    public static final String EVALUATION_RUN = "workspace:trust:evaluation:run";
    public static final String AGENT_QUERY = "workspace:trust:agent:query";
    public static final String AGENT_MANAGE = "workspace:trust:agent:manage";
    public static final String KEY_MANAGE = "workspace:trust:key:manage";
    public static final String SYS_USER_MANAGE = "workspace:sys:user:manage";
    public static final String SYS_ROLE_MANAGE = "workspace:sys:role:manage";
    public static final String SYS_AUDIT_QUERY = "workspace:sys:audit:query";
    public static final String OPENAPI_MANAGE = "workspace:sys:openapi:manage";
    public static final String SYS_NOTIFY_MANAGE = "workspace:sys:notify:manage";
    public static final String SYS_SETTING_MANAGE = "workspace:sys:setting:manage";
    public static final String PROMPT_QUERY = "workspace:trust:prompt:query";
    public static final String PROMPT_MANAGE = "workspace:trust:prompt:manage";
    public static final String DATASET_QUERY = "workspace:trust:dataset:query";
    public static final String DATASET_MANAGE = "workspace:trust:dataset:manage";
    public static final String MODEL_QUERY = "workspace:trust:model:query";
    public static final String MODEL_MANAGE = "workspace:trust:model:manage";

    public static final List<String> ALL = List.of(
            TRUST_OVERVIEW_QUERY, TRACE_QUERY, TRACE_CONTENT, FINDING_QUERY,
            INCIDENT_QUERY, INCIDENT_MANAGE, MONITOR_QUERY, MONITOR_MANAGE,
            EVALUATOR_QUERY, EVALUATOR_MANAGE, EVALUATION_QUERY, EVALUATION_RUN,
            AGENT_QUERY, AGENT_MANAGE, KEY_MANAGE,
            PROMPT_QUERY, PROMPT_MANAGE, DATASET_QUERY, DATASET_MANAGE, MODEL_QUERY, MODEL_MANAGE,
            SYS_USER_MANAGE, SYS_ROLE_MANAGE, SYS_AUDIT_QUERY, OPENAPI_MANAGE, SYS_NOTIFY_MANAGE,
            SYS_SETTING_MANAGE);

    public static final List<String> OPERATOR = List.of(
            TRUST_OVERVIEW_QUERY, TRACE_QUERY, TRACE_CONTENT, FINDING_QUERY,
            INCIDENT_QUERY, INCIDENT_MANAGE, MONITOR_QUERY, MONITOR_MANAGE,
            EVALUATOR_QUERY, EVALUATOR_MANAGE, EVALUATION_QUERY, EVALUATION_RUN,
            AGENT_QUERY, AGENT_MANAGE, KEY_MANAGE,
            PROMPT_QUERY, PROMPT_MANAGE, DATASET_QUERY, DATASET_MANAGE, MODEL_QUERY, MODEL_MANAGE);

    public static final List<String> VIEWER = List.of(
            TRUST_OVERVIEW_QUERY, TRACE_QUERY, FINDING_QUERY, INCIDENT_QUERY,
            MONITOR_QUERY, EVALUATOR_QUERY, EVALUATION_QUERY, AGENT_QUERY,
            PROMPT_QUERY, DATASET_QUERY, MODEL_QUERY);

    private Permissions() {
    }
}
