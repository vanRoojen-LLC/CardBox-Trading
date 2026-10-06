package com.cardpricer.cloud.clubsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What this resource group has cost so far this month, from Azure Cost Management, for the health dashboard. Asked
 * as the app's user-assigned managed identity (role "Cost Management Reader" on the resource group, see
 * cloud/infra/main.bicep), with a token from the Container Apps identity endpoint. Cost data only moves a few times a
 * day, so an answer is kept for an hour; a failure is kept for five minutes so a broken setup is not hammered.
 * Never throws: when anything is missing or fails, {@link Result#usd()} is null and {@link Result#error()} says why.
 */
@Component
public class AzureCost {
    public record Result(Double usd, String error) {
        static Result failed(String error) { return new Result(null, error); }
    }

    static final String RESOURCE = "https://management.azure.com/";
    static final String QUERY = """
            {"type":"ActualCost","timeframe":"MonthToDate","dataset":{"granularity":"None",\
            "aggregation":{"totalCost":{"name":"Cost","function":"Sum"}}}}""";
    private static final Duration KEEP = Duration.ofHours(1);
    private static final Duration KEEP_FAILURE = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(AzureCost.class);

    private record Cached(Result result, long until) {}

    private static class IdentityUnavailable extends Exception {
        IdentityUnavailable(String message) { super(message); }
    }

    private final String subscriptionId;
    private final String resourceGroup;
    private final String clientId;
    private final String identityEndpoint;
    private final String identityHeader;
    private final String managementUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();
    private volatile Cached cached;

    /** {@code managementUrl} overrides https://management.azure.com; the tests point it at a local stand-in. */
    public AzureCost(@Value("${app.azure-cost.subscription-id:}") String subscriptionId,
                     @Value("${app.azure-cost.resource-group:}") String resourceGroup,
                     @Value("${app.azure-cost.client-id:}") String clientId,
                     @Value("${app.azure-cost.identity-endpoint:}") String identityEndpoint,
                     @Value("${app.azure-cost.identity-header:}") String identityHeader,
                     @Value("${app.azure-cost.management-url:https://management.azure.com}") String managementUrl) {
        this.subscriptionId = subscriptionId.strip();
        this.resourceGroup = resourceGroup.strip();
        this.clientId = clientId.strip();
        this.identityEndpoint = identityEndpoint.strip();
        this.identityHeader = identityHeader.strip();
        this.managementUrl = managementUrl.endsWith("/") ? managementUrl.substring(0, managementUrl.length() - 1) : managementUrl;
    }

    /** Month-to-date cost in USD, from the last hour's answer when there is one. */
    public Result monthToDate() {
        Cached c = cached;
        if (c != null && System.currentTimeMillis() < c.until()) return c.result();
        synchronized (this) {
            c = cached;
            if (c != null && System.currentTimeMillis() < c.until()) return c.result();
            Result result = fetch();
            if (result.error() != null) log.warn("Azure cost unavailable: {}", result.error());
            cached = new Cached(result, System.currentTimeMillis() + (result.error() == null ? KEEP : KEEP_FAILURE).toMillis());
            return result;
        }
    }

    private Result fetch() {
        List<String> missing = new ArrayList<>();
        if (subscriptionId.isEmpty()) missing.add("AZURE_SUBSCRIPTION_ID");
        if (resourceGroup.isEmpty()) missing.add("AZURE_RESOURCE_GROUP");
        if (clientId.isEmpty()) missing.add("AZURE_CLIENT_ID");
        if (identityEndpoint.isEmpty() || identityHeader.isEmpty()) missing.add("managed identity endpoint");
        if (!missing.isEmpty()) return Result.failed("Not configured: " + String.join(", ", missing));
        try {
            String token = token();
            if (token == null) return Result.failed("Managed identity returned no token");
            var response = http.send(HttpRequest.newBuilder(URI.create(managementUrl + "/subscriptions/" + enc(subscriptionId)
                            + "/resourceGroups/" + enc(resourceGroup)
                            + "/providers/Microsoft.CostManagement/query?api-version=2023-03-01"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(QUERY)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Result.failed("Cost Management answered " + response.statusCode());
            return parse(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("Interrupted");
        } catch (IdentityUnavailable e) {
            return Result.failed(e.getMessage());
        } catch (Exception e) {
            return Result.failed("Cost query failed: " + e.getClass().getSimpleName());
        }
    }

    /** A token for Azure Resource Manager from the Container Apps identity endpoint, as the app's identity. */
    private String token() throws Exception {
        String sep = identityEndpoint.contains("?") ? "&" : "?";
        var response = http.send(HttpRequest.newBuilder(URI.create(identityEndpoint + sep + "api-version=2019-08-01&resource="
                        + enc(RESOURCE) + "&client_id=" + enc(clientId)))
                .timeout(Duration.ofSeconds(15))
                .header("X-IDENTITY-HEADER", identityHeader)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IdentityUnavailable("Managed identity endpoint answered " + response.statusCode());
        String token = json.readTree(response.body()).path("access_token").asText("");
        return token.isEmpty() ? null : token;
    }

    /**
     * Reads Cost Management's answer: {@code properties.columns} names the columns and {@code properties.rows} holds
     * one row (Cost, Currency) with granularity None. No rows means nothing has been billed yet this month.
     */
    static Result parse(String body) {
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(body);
        } catch (Exception e) {
            return Result.failed("Unreadable cost response");
        }
        JsonNode columns = root.path("properties").path("columns");
        JsonNode rows = root.path("properties").path("rows");
        if (!columns.isArray() || !rows.isArray()) return Result.failed("Unexpected cost response");
        int cost = -1, currency = -1;
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i).path("name").asText("");
            if (name.equalsIgnoreCase("Cost") || name.equalsIgnoreCase("totalCost") || name.equalsIgnoreCase("PreTaxCost")) cost = i;
            else if (name.equalsIgnoreCase("Currency") || name.equalsIgnoreCase("BillingCurrency")) currency = i;
        }
        if (cost < 0) return Result.failed("Cost response has no Cost column");
        double total = 0;
        for (JsonNode row : rows) {
            if (!row.path(cost).isNumber()) return Result.failed("Cost response has a non-numeric cost");
            if (currency >= 0) {
                String code = row.path(currency).asText("USD").toUpperCase(Locale.ROOT);
                if (!code.equals("USD")) return Result.failed("Billed in " + code + ", not USD");
            }
            total += row.path(cost).asDouble();
        }
        return new Result(Math.round(total * 100) / 100.0, null);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
