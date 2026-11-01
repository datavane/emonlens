package io.emonlens.server.ingest;

import java.util.Map;

import io.emonlens.server.storage.entity.SpanEventEntity;

import lombok.Value;

/** ingest 队列元素：span 元数据 + 可选采集内容 */
@Value
public class SpanEnvelope {

    SpanEventEntity span;

    Map<String, String> contents;
}
