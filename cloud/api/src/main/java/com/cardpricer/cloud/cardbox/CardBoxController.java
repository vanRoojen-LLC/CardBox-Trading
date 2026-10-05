package com.cardpricer.cloud.cardbox;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The Team and Admin screens' window onto CardBox's people, stores and roles: /api/cardbox/X is CardBox's /api/X,
 * called from this server with the signed-in person's own CardBox token. Only the endpoints in the shared contract
 * pass through, and CardBox decides what each person may see and change. 404 while the link is switched off.
 */
@RestController
public class CardBoxController {
    private record Route(String method, Pattern path) {}

    private static final String ID = "[A-Za-z0-9_-]{1,64}";
    private static final List<Route> ROUTES = List.of(
            new Route("GET", Pattern.compile("account/roles")),
            new Route("GET", Pattern.compile("people")),
            new Route("GET", Pattern.compile("stores")),
            new Route("POST", Pattern.compile("stores")),
            new Route("PATCH", Pattern.compile("stores/" + ID)),
            new Route("POST", Pattern.compile("role-grants")),
            new Route("DELETE", Pattern.compile("role-grants/" + ID)),
            new Route("GET", Pattern.compile("role-catalog")),
            new Route("GET", Pattern.compile("role-events")));

    private final CardBoxClient cardbox;
    private final CardBoxTokens tokens;
    private final StoreNames storeNames;

    public CardBoxController(CardBoxClient cardbox, CardBoxTokens tokens, StoreNames storeNames) {
        this.cardbox = cardbox;
        this.tokens = tokens;
        this.storeNames = storeNames;
    }

    @RequestMapping("/api/cardbox/**")
    public ResponseEntity<JsonNode> forward(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        if (!cardbox.enabled()) throw ApiException.notFound("Not found");
        String path = request.getRequestURI().substring("/api/cardbox/".length());
        String method = request.getMethod();
        if (ROUTES.stream().noneMatch(r -> r.method().equals(method) && r.path().matcher(path).matches()))
            throw ApiException.notFound("Not found");
        CurrentUser user = CurrentUser.of(request);
        String token = tokens.find(user.auth0Sub()).orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "Your CardBox sign-in has expired. Please sign out and sign in again."));
        String query = "GET".equals(method) && request.getQueryString() != null ? "?" + request.getQueryString() : "";
        CardBoxClient.Result result;
        try {
            result = cardbox.call(token, method, "/api/" + path + query, "GET".equals(method) || "DELETE".equals(method) ? null : body);
        } catch (CardBoxClient.Unavailable e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, e.getMessage());
        }
        if (!result.ok()) {
            if (result.status() == 401) tokens.forget(user.auth0Sub());
            HttpStatus status = HttpStatus.resolve(result.status());
            // CardBox's own message, as it is, in the shape the app shows errors.
            throw new ApiException(status == null ? HttpStatus.BAD_GATEWAY : status, result.detail());
        }
        if ("account/roles".equals(path)) {
            // Keep the Admin tab in step with CardBox between sign-ins.
            tokens.setPlatformOwner(user.auth0Sub(), CardBoxSignIn.parse(result.body()).platformOwner());
        }
        // A store renamed on CardBox shows its new name here straight away, not only after its people sign in again.
        if ("account/roles".equals(path) || path.equals("stores") || path.startsWith("stores/")) storeNames.adoptFrom(result.body());
        return ResponseEntity.status(result.status()).body(result.body().isNull() ? null : result.body());
    }
}
