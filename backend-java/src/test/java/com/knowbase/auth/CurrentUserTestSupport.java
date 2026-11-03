package com.knowbase.auth;

/** Test-only bridge for packages outside {@code com.knowbase.auth}. */
public final class CurrentUserTestSupport {

    private CurrentUserTestSupport() {
    }

    public static void authenticate(AuthUser user) {
        CurrentUser.set(user);
    }

    public static void clear() {
        CurrentUser.clear();
    }
}
