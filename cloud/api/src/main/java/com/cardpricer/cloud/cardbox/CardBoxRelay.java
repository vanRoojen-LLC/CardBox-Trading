package com.cardpricer.cloud.cardbox;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Calls CardBox as the signed-in person, with their stored CardBox token, and turns CardBox's answer into this
 * app's: its error messages pass through as they are, and a refused token is forgotten so the next sign-in renews it.
 */
@Component
public class CardBoxRelay {
    private final CardBoxClient cardbox;
    private final CardBoxTokens tokens;

    public CardBoxRelay(CardBoxClient cardbox, CardBoxTokens tokens) {
        this.cardbox = cardbox;
        this.tokens = tokens;
    }

    public boolean enabled() { return cardbox.enabled(); }

    public CardBoxClient.Result call(CurrentUser user, String method, String path, JsonNode body) {
        if (!cardbox.enabled()) throw ApiException.notFound("Not found");
        String token = tokens.find(user.auth0Sub()).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "Your CardBox sign-in has expired. Please sign out and sign in again."));
        CardBoxClient.Result result;
        try {
            result = cardbox.call(token, method, path, "GET".equals(method) || "DELETE".equals(method) ? null : body);
        } catch (CardBoxClient.Unavailable e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        if (!result.ok()) {
            if (result.status() == 401) tokens.forget(user.auth0Sub());
            HttpStatus status = HttpStatus.resolve(result.status());
            // CardBox's own message, as it is, in the shape the app shows errors.
            throw new ApiException(status == null ? HttpStatus.BAD_GATEWAY : status, result.detail());
        }
        return result;
    }
}
