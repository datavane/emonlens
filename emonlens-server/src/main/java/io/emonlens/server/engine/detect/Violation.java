package io.emonlens.server.engine.detect;

import java.util.Map;

import lombok.Value;

/**
 * 检测违规项：检测器输出，由 DetectionService 组装为 Finding。
 */
@Value
public class Violation {

    String message;

    /** 违规关联的 span，可空 */
    String spanId;

    Map<String, Object> evidence;
}
