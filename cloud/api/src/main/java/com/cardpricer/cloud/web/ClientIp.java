package com.cardpricer.cloud.web;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIp {
    private ClientIp() {}

    /**
     * Behind Cloudflare (the cardbox-trading tunnel on Rackspace Spot) the caller is CF-Connecting-IP, which a client
     * cannot set. Azure's Container Apps ingress appends the caller to X-Forwarded-For.
     */
    public static String of(HttpServletRequest request) {
        String cloudflare = request.getHeader("CF-Connecting-IP");
        if (cloudflare != null && !cloudflare.isBlank()) return cloudflare.trim();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim();
        return request.getRemoteAddr();
    }
}
