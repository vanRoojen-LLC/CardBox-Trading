package com.cardpricer.cloud.auth;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * {@code admin} is the platform owner: the configured owner email, verified by Auth0, or (with the CardBox link on)
 * someone CardBox made a platform owner. {@code preview} is CardBox's Preview list, shared with CardBox Club: every
 * platform owner, plus (with the CardBox link on) everyone CardBox gave preview_access. It opens the preview games.
 */
public record CurrentUser(UUID userId, UUID tenantId, String role, String name, String email, boolean admin, String auth0Sub,
                          boolean preview) {
    static final String ATTRIBUTE = CurrentUser.class.getName();

    public boolean owner() { return "owner".equals(role); }

    /** Only present on the requests AuthFilter guards. */
    public static CurrentUser of(HttpServletRequest request) {
        return (CurrentUser) request.getAttribute(ATTRIBUTE);
    }
}
