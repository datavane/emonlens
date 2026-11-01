package io.emonlens.server.auth;

import java.util.Set;

import lombok.Value;

/** 请求级登录上下文（由 AuthenticationInterceptor 写入，请求结束清理）。 */
@Value
public class CurrentUser {

    String userId;
    String username;
    boolean superUser;
    String workspaceId;
    Set<String> permissions;

    public boolean hasPermission(String permission) {
        return superUser || (permissions != null && permissions.contains(permission));
    }
}
