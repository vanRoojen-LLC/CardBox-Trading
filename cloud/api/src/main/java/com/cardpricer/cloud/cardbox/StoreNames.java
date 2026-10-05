package com.cardpricer.cloud.cardbox;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * A CardBox store's name is CardBox's, and Trading only shows it. Everything here and in the sync goes by the store's
 * id ({@code tenants.cardbox_store_id}), so a rename can't break a link; this copies the new name onto Trading's row
 * whenever CardBox says it: at sign-in, in any stores answer passing through, and when Club sends a rename.
 */
@Component
public class StoreNames {
    private final JdbcTemplate jdbc;

    public StoreNames(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Sets the name of the Trading store tied to {@code cardboxStoreId}. Returns how many rows changed (0 or 1). */
    public int adopt(String cardboxStoreId, String name) {
        String clean = name == null ? "" : name.trim();
        if (cardboxStoreId == null || cardboxStoreId.isBlank() || clean.isEmpty()) return 0;
        if (clean.length() > 120) clean = clean.substring(0, 120);
        return jdbc.update("UPDATE tenants SET name = ? WHERE cardbox_store_id = ? AND name <> ?", clean, cardboxStoreId, clean);
    }

    /** Takes the names from a CardBox answer: a store, a list of stores, or a roles answer with {@code stores}. */
    public void adoptFrom(JsonNode body) {
        if (body == null) return;
        if (body.isArray()) {
            for (JsonNode store : body) adoptFrom(store);
        } else if (body.has("stores")) {
            adoptFrom(body.path("stores"));
        } else if (body.has("id") && body.has("name")) {
            adopt(body.path("id").asText(""), body.path("name").asText(""));
        }
    }
}
