package io.emonlens.server.engine.eval;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 一次 Judge 调用使用的不可变模板快照。 */
@Data
@AllArgsConstructor
public class JudgeTemplate {

    public static final String SOURCE_PLATFORM = "PLATFORM";
    public static final String SOURCE_WORKSPACE = "WORKSPACE";

    private String key;
    private Integer version;
    private String systemPrompt;
    private String userPrompt;
    private String outputSchema;
    private String hash;
    private String source;
}
