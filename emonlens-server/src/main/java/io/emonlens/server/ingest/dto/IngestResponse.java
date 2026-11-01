package io.emonlens.server.ingest.dto;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngestResponse {

    private int acceptedCount;

    /** 逐条校验失败明细（index 为批内下标） */
    private List<RejectedItem> rejected;

    /** 本次请求中因队列满被丢弃的条数（低风险丢弃策略） */
    private int droppedInRequest;

    /** 实例累计丢弃总数（可观测） */
    private long droppedTotal;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RejectedItem {
        private int index;
        private String reason;
    }
}
