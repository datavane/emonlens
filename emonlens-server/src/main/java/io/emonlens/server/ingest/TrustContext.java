package io.emonlens.server.ingest;

import lombok.Value;

/**
 * ingest key 鉴权成功后解析出的租户上下文，随请求在实例内传递。
 */
@Value
public class TrustContext {

    String keyId;
    String workspaceId;
}
