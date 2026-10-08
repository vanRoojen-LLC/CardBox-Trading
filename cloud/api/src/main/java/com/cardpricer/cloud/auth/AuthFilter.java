package com.cardpricer.cloud.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.cardpricer.cloud.cardbox.CardBoxClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * Guards the paid store workflow under /api/app/**: requires a valid session and a store
 * whose subscription is active or still in trial. /api/admin/** needs the platform owner instead, and
 * /api/cardbox/** (people and roles, which CardBox itself guards) and /api/support/** (Help & feedback, which
 * must work even after a trial ends) only a session.
 * The free price check is never guarded.
 */
@Component
public class AuthFilter extends OncePerRequestFilter {
    public static final String COOKIE = "occ_session";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final SessionTokens tokens;
    private final JdbcTemplate jdbc;
    private final String ownerEmail;
    private final boolean cardbox;

    public AuthFilter(SessionTokens tokens, JdbcTemplate jdbc, CardBoxClient cardbox, @Value("${app.owner-email:}") String ownerEmail) {
        this.tokens = tokens;
        this.jdbc = jdbc;
        this.cardbox = cardbox.enabled();
        this.ownerEmail = ownerEmail.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/app/") && !path.startsWith("/api/admin/") && !path.startsWith("/api/cardbox/")
                && !path.startsWith("/api/support/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // CSRF: browsers cannot send a cross-site JSON body without a CORS preflight, which we never allow.
        if (!SAFE_METHODS.contains(request.getMethod())) {
            String type = request.getContentType();
            if (type == null || !type.startsWith("application/json")) {
                reject(response, 415, "Requests must be JSON");
                return;
            }
        }
        var userId = tokens.verify(sessionCookie(request));
        if (userId.isEmpty()) {
            signIn(request, response);
            return;
        }
        var rows = jdbc.query("""
                SELECT u.id, u.tenant_id, u.role, u.name, u.email, u.auth0_sub IS NOT NULL,
                       t.plan_status = 'active' OR (t.plan_status = 'trial' AND t.trial_ends_at > now()) AS entitled,
                       coalesce(c.platform_owner, false), u.auth0_sub, coalesce(c.preview, false)
                FROM users u JOIN tenants t ON t.id = u.tenant_id LEFT JOIN cardbox_tokens c ON c.auth0_sub = u.auth0_sub
                WHERE u.id = ? AND u.removed_at IS NULL
                AND (NOT ? OR t.cardbox_store_id IS NOT NULL OR coalesce(c.platform_owner, false))""",
                (rs, i) -> {
                    boolean admin = rs.getBoolean(6) && !ownerEmail.isEmpty() && ownerEmail.equalsIgnoreCase(rs.getString(5))
                            // With the link on, CardBox's platform_owner role (as of the last sign-in) counts too.
                            || cardbox && rs.getBoolean(8);
                    // Preview follows CardBox's Preview list, the same list CardBox Club uses; owners are always on it.
                    boolean preview = admin || cardbox && rs.getBoolean(10);
                    return new Object[]{
                            new CurrentUser(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                                    rs.getString(4), rs.getString(5), admin, rs.getString(9), preview),
                            rs.getBoolean(7)};
                },
                userId.get(), cardbox);
        if (rows.isEmpty()) {
            signIn(request, response);
            return;
        }
        CurrentUser user = (CurrentUser) rows.getFirst()[0];
        if (request.getRequestURI().startsWith("/api/cardbox/")) {
            // CardBox decides what this person may see and change.
        } else if (request.getRequestURI().startsWith("/api/support/")) {
            // Anyone signed in can ask for help, whatever the state of their store's subscription.
        } else if (request.getRequestURI().startsWith("/api/admin/")) {
            // The platform admin works whatever the state of their own store's subscription.
            if (!user.admin()) {
                reject(response, 403, "Only the platform owner can do this");
                return;
            }
        } else if (!(Boolean) rows.getFirst()[1]) {
            reject(response, 402, "Your store's subscription has ended");
            return;
        }
        request.setAttribute(CurrentUser.ATTRIBUTE, user);
        chain.doFilter(request, response);
    }

    static String sessionCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies()).filter(c -> COOKIE.equals(c.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }

    /**
     * No session: a person who opened the address in their browser goes to sign in and comes back here; the app's own
     * requests and other API clients get 401 JSON, which the app turns into its sign-in page.
     */
    private static void signIn(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (browserVisit(request)) {
            String here = request.getRequestURI() + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
            response.sendRedirect("/api/auth/login?returnTo=" + URLEncoder.encode(here, StandardCharsets.UTF_8));
            return;
        }
        reject(response, 401, "Please sign in");
    }

    /** A top-level page load (the address bar or a link), not a fetch: browsers ask for HTML only then. */
    static boolean browserVisit(HttpServletRequest request) {
        if (!"GET".equals(request.getMethod())) return false;
        String accept = request.getHeader("Accept"), mode = request.getHeader("Sec-Fetch-Mode");
        if (mode != null) return mode.equals("navigate");
        return accept != null && accept.contains("text/html");
    }

    private static void reject(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
