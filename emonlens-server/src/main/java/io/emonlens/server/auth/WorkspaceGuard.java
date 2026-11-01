package io.emonlens.server.auth;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;

/** 行级隔离守卫：实体不属于当前工作空间时统一按 NOT_FOUND 处理（避免泄露存在性）。 */
public final class WorkspaceGuard {

    private WorkspaceGuard() {
    }

    public static void owned(String entityWorkspaceId) {
        if (!CurrentContext.require().getWorkspaceId().equals(entityWorkspaceId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "resource not found");
        }
    }
}
