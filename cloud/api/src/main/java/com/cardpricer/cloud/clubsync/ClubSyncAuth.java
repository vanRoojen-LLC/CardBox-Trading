package com.cardpricer.cloud.clubsync;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Checks the machine-to-machine token cardbox.club sends with every sync call: an Auth0 client-credentials token
 * for Trading's API audience, signed by the shared Auth0 tenant, carrying the inventory:sync scope, issued to one of
 * the allowed client ids (CardBox's). Nobody is signed in when Club syncs, so no person's token is involved.
 * Switched off unless {@code app.club-sync.enabled} is true.
 */
@Component
public class ClubSyncAuth {
    public static final String SCOPE = "inventory:sync";

    private final boolean enabled;
    private final String issuer;
    private final String audience;
    private final Set<String> clientIds;
    private volatile DefaultJWTProcessor<SecurityContext> processor;

    public ClubSyncAuth(@Value("${app.club-sync.enabled:false}") boolean enabled,
                        @Value("${app.auth0.domain:}") String domain,
                        @Value("${app.auth0.issuer:}") String issuer,
                        @Value("${app.club-sync.audience:https://cardbox.trading/api}") String audience,
                        @Value("${app.club-sync.client-ids:}") String clientIds) {
        String base = issuer.isBlank() ? (domain.isBlank() ? "" : "https://" + domain.trim()) : issuer.trim();
        this.issuer = base.isEmpty() || base.endsWith("/") ? base : base + "/";
        this.audience = audience.trim();
        this.clientIds = Set.copyOf(Arrays.stream(clientIds.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        // Without an issuer or an allowed client there is nobody to accept.
        this.enabled = enabled && !this.issuer.isEmpty() && !this.clientIds.isEmpty();
    }

    public boolean enabled() { return enabled; }

    /** True when the Authorization header carries a valid sync token from an allowed client. */
    public boolean accepts(String authorization) {
        if (!enabled || authorization == null || !authorization.startsWith("Bearer ")) return false;
        JWTClaimsSet claims;
        try {
            claims = processor().process(authorization.substring(7).trim(), null);
        } catch (Exception e) {
            return false;
        }
        Object azp = claims.getClaim("azp");
        String client = azp instanceof String s ? s : claims.getSubject() != null && claims.getSubject().endsWith("@clients")
                ? claims.getSubject().substring(0, claims.getSubject().length() - "@clients".length()) : null;
        return client != null && clientIds.contains(client) && hasScope(claims);
    }

    /** Auth0 puts the granted scope in {@code scope} (space separated) and, with RBAC on, in {@code permissions}. */
    private static boolean hasScope(JWTClaimsSet claims) {
        Object scope = claims.getClaim("scope");
        if (scope instanceof String s && List.of(s.split(" ")).contains(SCOPE)) return true;
        Object permissions = claims.getClaim("permissions");
        return permissions instanceof List<?> list && list.contains(SCOPE);
    }

    private DefaultJWTProcessor<SecurityContext> processor() throws MalformedURLException {
        if (processor == null) {
            synchronized (this) {
                if (processor == null) {
                    JWKSource<SecurityContext> keys = JWKSourceBuilder.create(new URL(issuer + ".well-known/jwks.json")).build();
                    var p = new DefaultJWTProcessor<SecurityContext>();
                    p.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, keys));
                    p.setJWTClaimsSetVerifier(new DefaultJWTClaimsVerifier<>(audience,
                            new JWTClaimsSet.Builder().issuer(issuer).build(), Set.of("sub", "exp")));
                    processor = p;
                }
            }
        }
        return processor;
    }
}
