package io.emonlens.server.auth;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;

public final class CurrentContext {

    private static final ThreadLocal<CurrentUser> TL = new ThreadLocal<>();

    private CurrentContext() {
    }

    public static void set(CurrentUser user) {
        TL.set(user);
    }

    public static CurrentUser require() {
        CurrentUser user = TL.get();
        if (user == null) {
            throw new EmonLensException(ErrorCode.UNAUTHORIZED, "未登录或凭据已失效");
        }
        return user;
    }

    public static void clear() {
        TL.remove();
    }
}
