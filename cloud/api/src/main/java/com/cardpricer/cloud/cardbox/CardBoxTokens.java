package com.cardpricer.cloud.cardbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Keeps each person's CardBox access token on the server, never in the browser. Tokens are encrypted with a key
 * derived from the session secret, so a database copy alone can't be used to call CardBox as anyone.
 */
@Component
public class CardBoxTokens {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final JdbcTemplate jdbc;
    private final SecretKeySpec key;

    public CardBoxTokens(JdbcTemplate jdbc, @Value("${app.session-secret}") String secret) {
        this.jdbc = jdbc;
        try {
            byte[] derived = MessageDigest.getInstance("SHA-256")
                    .digest(("cardbox-token|" + secret).getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(derived, "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public void save(String sub, String accessToken, Instant expiresAt, boolean platformOwner, boolean preview) {
        jdbc.update("""
                INSERT INTO cardbox_tokens (auth0_sub, token, expires_at, platform_owner, preview) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (auth0_sub) DO UPDATE SET token = excluded.token, expires_at = excluded.expires_at,
                    platform_owner = excluded.platform_owner, preview = excluded.preview, updated_at = now()""",
                sub, encrypt(accessToken), Timestamp.from(expiresAt), platformOwner, preview);
    }

    /** CardBox's platform owner role and Preview list, as of the person's latest roles answer. */
    public void setRoles(String sub, CardBoxSignIn.Roles roles) {
        jdbc.update("UPDATE cardbox_tokens SET platform_owner = ?, preview = ? WHERE auth0_sub = ?",
                roles.platformOwner(), roles.preview(), sub);
    }

    /** The person's token while it is still valid; empty means they need to sign in again. */
    public Optional<String> find(String sub) {
        if (sub == null) return Optional.empty();
        return jdbc.query("SELECT token FROM cardbox_tokens WHERE auth0_sub = ? AND expires_at > now()",
                (rs, i) -> rs.getBytes(1), sub).stream().findFirst().map(this::decrypt);
    }

    public void forget(String sub) {
        if (sub != null) jdbc.update("DELETE FROM cardbox_tokens WHERE auth0_sub = ?", sub);
    }

    private byte[] encrypt(String token) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            byte[] sealed = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private String decrypt(byte[] stored) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, stored, 0, 12));
            return new String(cipher.doFinal(stored, 12, stored.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Sealed with an earlier session secret: treat as signed out.
            return null;
        }
    }
}
