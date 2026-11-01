package io.emonlens.server.engine.eval;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

/** Judge 从候选输出中抽取的一条可核验断言。 */
@Data
public class Claim {

    private String id;
    private String text;
    /** PASS / FAIL / UNKNOWN */
    private String verdict;
    private List<String> evidenceRefs = new ArrayList<>();
    private String reason;
}
