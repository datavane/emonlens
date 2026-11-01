package io.emonlens.server.notify;

import io.emonlens.server.storage.entity.NotifyRecordEntity;

import java.util.Map;

/** 通知渠道扩展点：按 channel 标识匹配；实现从 config JSON 读取自身配置。 */
public interface NotifyChannel {

    String channel();

    /** 发送一条通知；返回 target（收件人/URL 等落记录用）。失败抛异常由派发层记录重试。 */
    String send(NotifyRecordEntity record, String configJson, String subject, String content,
                Map<String, Object> variables) throws Exception;
}
