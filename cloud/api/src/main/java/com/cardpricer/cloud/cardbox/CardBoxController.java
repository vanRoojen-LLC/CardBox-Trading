package com.cardpricer.cloud.cardbox;

import com.cardpricer.cloud.auth.CurrentUser;
import com.cardpricer.cloud.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The Admin screen's window onto CardBox's stores and roles: /api/cardbox/X is CardBox's /api/X, called from this
 * server with the signed-in person's own CardBox token. Only the endpoints in the shared contract pass through, and
 * CardBox decides what each person may see and change. 404 while the link is switched off.
 *
 * <p>CardBox's list of every account (people) and its grant-by-email endpoint are deliberately not here: a store's
 * team is managed through {@link TeamController}, which only ever shows the people in that store and adds new
 * ones by email invite.
 */
@RestController
public class CardBoxController {
    private record Route(String method, Pattern path) {}

    private static final String ID = "[A-Za-z0-9_-]{1,64}";
    private static final List<Route> ROUTES = List.of(
            new Route("GET", Pattern.compile("account/roles")),
            new Route("GET", Pattern.compile("stores")),
            new Route("POST", Pattern.compile("stores")),
            new Route("PATCH", Pattern.compile("stores/" + ID)),
            new Route("GET", Pattern.compile("role-catalog")),
            new Route("GET", Pattern.compile("role-events")));

    private final CardBoxRelay relay;
    private final CardBoxTokens tokens;
    private final StoreNames storeNames;

    public CardBoxController(CardBoxRelay relay, CardBoxTokens tokens, StoreNames storeNames) {
        this.relay = relay;
        this.tokens = tokens;
        this.storeNames = storeNames;
    }

    @RequestMapping("/api/cardbox/**")
    public ResponseEntity<JsonNode> forward(@RequestBody(required = false) JsonNode body, HttpServletRequest request) {
        if (!relay.enabled()) throw ApiException.notFound("Not found");
        String path = request.getRequestURI().substring("/api/cardbox/".length());
        String method = request.getMethod();
        if (ROUTES.stream().noneMatch(r -> r.method().equals(method) && r.path().matcher(path).matches()))
            throw ApiException.notFound("Not found");
        CurrentUser user = CurrentUser.of(request);
        String query = "GET".equals(method) && request.getQueryString() != null ? "?" + request.getQueryString() : "";
        CardBoxClient.Result result = relay.call(user, method, "/api/" + path + query, body);
        if ("account/roles".equals(path)) {
            // Keep the Admin tab in step with CardBox between sign-ins.
            tokens.setPlatformOwner(user.auth0Sub(), CardBoxSignIn.parse(result.body()).platformOwner());
        }
        // A store renamed on CardBox shows its new name here straight away, not only after its people sign in again.
        if ("account/roles".equals(path) || path.equals("stores") || path.startsWith("stores/")) storeNames.adoptFrom(result.body());
        return ResponseEntity.status(result.status()).body(result.body().isNull() ? null : result.body());
    }
}
