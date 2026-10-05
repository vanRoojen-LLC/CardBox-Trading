package com.cardpricer.cloud.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/**
 * Opens issues in Trading's GitHub repository with a fine-grained token that can only read and write that repo's
 * issues. Switched off (and never called) while {@code app.support.github.token} is empty.
 */
@Component
public class GitHubIssues {
    /** GitHub's answer; {@code rateLimited} when GitHub asked us to slow down rather than refused. */
    public record Result(int status, JsonNode body, boolean rateLimited) {
        public boolean created() { return status == 201; }

        /** GitHub's own message, one line and short; never echoes anything we sent. */
        public String message() {
            String message = body.path("message").asText("");
            message = message.replaceAll("\\s+", " ").strip();
            return message.length() > 200 ? message.substring(0, 200) : message;
        }
    }

    public static class Unavailable extends Exception {
        public Unavailable(String message) { super(message); }
    }

    private final String token;
    private final String repo;
    private final String apiUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json = new ObjectMapper();

    /** {@code apiUrl} overrides https://api.github.com; the tests point it at a local stand-in. */
    public GitHubIssues(@Value("${app.support.github.token:}") String token,
                        @Value("${app.support.github.repo:vanRoojen-LLC/OCC_PRICER}") String repo,
                        @Value("${app.support.github.api-url:https://api.github.com}") String apiUrl) {
        this.token = token == null ? "" : token.strip();
        this.repo = repo == null || repo.isBlank() ? "vanRoojen-LLC/OCC_PRICER" : repo.strip();
        if (!this.repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
            throw new IllegalArgumentException("app.support.github.repo must look like owner/name");
        this.apiUrl = apiUrl.endsWith("/") ? apiUrl.substring(0, apiUrl.length() - 1) : apiUrl;
    }

    public boolean enabled() { return !token.isEmpty(); }

    public Result create(String title, String body, List<String> labels) throws Unavailable {
        var issue = new LinkedHashMap<String, Object>();
        issue.put("title", title);
        issue.put("body", body);
        if (labels != null && !labels.isEmpty()) issue.put("labels", labels);
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(apiUrl + "/repos/" + repo + "/issues"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(issue))).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode parsed;
            try {
                parsed = response.body() == null || response.body().isBlank() ? NullNode.getInstance() : json.readTree(response.body());
            } catch (IOException e) {
                parsed = NullNode.getInstance();
            }
            Optional<String> remaining = response.headers().firstValue("x-ratelimit-remaining");
            boolean rateLimited = response.statusCode() == 429
                    || response.statusCode() == 403 && (remaining.map("0"::equals).orElse(false)
                    || response.headers().firstValue("retry-after").isPresent());
            return new Result(response.statusCode(), parsed, rateLimited);
        } catch (IOException e) {
            throw new Unavailable("GitHub could not be reached");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Unavailable("Interrupted");
        }
    }
}
