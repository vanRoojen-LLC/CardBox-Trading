package com.cardpricer.service;

import com.cardpricer.util.AppDataDirectory;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages autosave of in-progress trade sessions to disk for crash recovery.
 *
 * <p>Typed drafts live in the local ledger, keyed by trade ID, including their edit-parent revision.
 * Edits debounce saves for two seconds; orderly close flushes the save queue.
 * The legacy autosave adapter retains a hash-named backup before recovery.
 */
public class TradeSessionService {

    /**
     * A single table row captured for autosave purposes.
     *
     * @param code      display code shown in the table (e.g. "TDM 3f")
     * @param cardName  card name
     * @param condition condition string (e.g. "NM")
     * @param qty       quantity
     * @param unitPrice unit price
     */
    public record SessionRow(
            String code,
            String cardName,
            String condition,
            int qty,
            BigDecimal unitPrice) {}

    /**
     * A saved session containing trader/customer info and all table rows.
     *
     * @param traderName   trader name (may be empty)
     * @param customerName customer name (may be empty)
     * @param rows         list of session rows
     */
    public record SavedSession(
            String traderName,
            String customerName,
            List<SessionRow> rows) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    private static final DraftSaveQueue SAVES=new DraftSaveQueue(
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread thread=new Thread(r,"draft-save");thread.setDaemon(true);return thread;
            }),32);

    private static TradeRepository repository() {
        try { return new TradeRepository(AppDataDirectory.root().toPath().resolve("ledger/trades.sqlite")); }
        catch(Exception failure) { throw new IllegalStateException("Could not open draft storage",failure); }
    }
    private static Path draftPath() { return autosavePath().resolveSibling("draft-v1.json"); }
    /** Import the old single-file recovery only after its transaction is durable. */
    private static synchronized void migrate() {
        if(!Files.exists(draftPath())) return;
        try {
            var json=new JSONObject(Files.readString(draftPath()));
            var draft=com.cardpricer.model.TradeDraft.fromJson(json);
            repository().saveRecoveryDraft(draft,json.has("editingRevision") ? json.getLong("editingRevision") : null);
            Path backup=draftPath().resolveSibling("migrated-draft-"+draft.id()+"-"+draft.revision()+".json");
            if(!Files.exists(backup)) com.cardpricer.util.AtomicFiles.write(backup,json.toString());
            Files.delete(draftPath());
        } catch(Exception failure) { throw new IllegalStateException("Could not migrate the saved draft; original file retained",failure); }
    }
    public static java.util.List<TradeRepository.DraftSummary> drafts() {
        migrate();
        try { return repository().recoveryDrafts(); }
        catch(Exception failure) { throw new IllegalStateException("Could not list drafts",failure); }
    }
    public static TradeRepository.RecoveryDraft load(java.util.UUID id) {
        migrate();
        try { return repository().recoveryDraft(id); }
        catch(Exception failure) { throw new IllegalStateException("Could not read the draft",failure); }
    }
    public static TradeRepository.RecoveryDraft find(java.util.UUID id) {
        migrate();
        try { return repository().findRecoveryDraft(id); }
        catch(Exception failure) { throw new IllegalStateException("Could not read the draft",failure); }
    }
    public static void queueDraft(com.cardpricer.model.TradeDraft draft) { queueDraft(draft,null); }
    public static void queueDraft(com.cardpricer.model.TradeDraft draft,Long editingRevision) {
        queueDraft(draft.id(),()->draft,editingRevision,ignored -> {});
    }
    public static void queueDraft(java.util.UUID id,java.util.function.Supplier<com.cardpricer.model.TradeDraft> capture,
            Long editingRevision,java.util.function.Consumer<RuntimeException> completed) {
        SAVES.submit(id,()->saveDraft(capture.get(),editingRevision),completed);
    }
    public static void flush() { SAVES.flush(java.time.Duration.ofSeconds(10)); }
    public static boolean hasTypedDraft() { return Files.exists(draftPath()) || !drafts().isEmpty(); }
    public static com.cardpricer.model.TradeDraft loadDraft() throws java.io.IOException {
        var drafts=drafts();
        if(drafts.isEmpty()) throw new java.io.IOException("No recovery draft");
        return load(drafts.getFirst().id()).draft();
    }
    public static Long loadEditingRevision() throws java.io.IOException {
        var drafts=drafts();
        if(drafts.isEmpty()) throw new java.io.IOException("No recovery draft");
        return drafts.getFirst().editingRevision();
    }
    public static void saveDraft(com.cardpricer.model.TradeDraft draft) { saveDraft(draft,null); }
    public static void saveDraft(com.cardpricer.model.TradeDraft draft,Long editingRevision) {
        try { repository().saveRecoveryDraft(draft,editingRevision); }
        catch(Exception failure) { throw new IllegalStateException("Could not save the draft",failure); }
    }
    public static boolean hasAutosave() { return Files.exists(autosavePath()); }
    /** Delete only the named draft; other parked work must survive a save or discard. */
    public static void discard(java.util.UUID id) {
        SAVES.discardFailure(id,java.time.Duration.ofSeconds(10));
        try { repository().deleteDraft(id); }
        catch(Exception failure) { throw new IllegalStateException("Could not discard the draft",failure); }
    }
    /** Compatibility cleanup for the previous single-session API. */
    public static void clearAutosave() {
        flush();
        var drafts=drafts();
        if(!drafts.isEmpty()) discard(drafts.getFirst().id());
        clearLegacyAutosave();
    }
    public static void clearLegacyAutosave() {
        try { Files.deleteIfExists(autosavePath()); }
        catch(Exception failure) { throw new IllegalStateException("Could not clear legacy recovery",failure); }
    }

    /**
     * Serialises the current session to the autosave file.
     *
     * @param traderName   trader name field content
     * @param customerName customer name field content
     * @param rows         current table rows
     */
    public static void save(String traderName, String customerName, List<SessionRow> rows) {
        try {
            JSONObject root = new JSONObject();
            root.put("traderName",   traderName   == null ? "" : traderName);
            root.put("customerName", customerName == null ? "" : customerName);

            JSONArray arr = new JSONArray();
            for (SessionRow row : rows) {
                JSONObject obj = new JSONObject();
                obj.put("code",      row.code());
                obj.put("cardName",  row.cardName());
                obj.put("condition", row.condition());
                obj.put("qty",       row.qty());
                obj.put("unitPrice", row.unitPrice().toPlainString());
                arr.put(obj);
            }
            root.put("rows", arr);

            Path path = autosavePath();
            Files.createDirectories(path.getParent());
            com.cardpricer.util.AtomicFiles.write(path, root.toString());
        } catch (Exception ignored) {
            // Never crash the app over an autosave failure
        }
    }

    /**
     * Loads the autosave file and returns a {@link SavedSession}, or {@code null}
     * if the file cannot be read or is malformed.
     *
     * @return parsed session, or {@code null} on any error
     */
    public static SavedSession load() {
        try {
            String content = Files.readString(autosavePath(), StandardCharsets.UTF_8);
            Path backup = autosavePath().resolveSibling("legacy-autosave-" + com.cardpricer.util.AtomicFiles.hash(content.getBytes(StandardCharsets.UTF_8)) + ".json");
            if (!Files.exists(backup)) com.cardpricer.util.AtomicFiles.write(backup, content);
            JSONObject root = new JSONObject(content);
            String traderName   = root.optString("traderName",   "");
            String customerName = root.optString("customerName", "");

            JSONArray arr = root.optJSONArray("rows");
            List<SessionRow> rows = new ArrayList<>();
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String code      = obj.optString("code",      "MISC 1");
                    String cardName  = obj.optString("cardName",  "Unknown Card");
                    String condition = obj.optString("condition", "NM");
                    int qty          = obj.optInt("qty", 1);
                    BigDecimal price;
                    try {
                        price = new BigDecimal(obj.optString("unitPrice", "0.00"));
                    } catch (NumberFormatException e) {
                        price = BigDecimal.ZERO;
                    }
                    rows.add(new SessionRow(code, cardName, condition, qty, price));
                }
            }
            return new SavedSession(traderName, customerName, rows);
        } catch (Exception e) {
            return null;
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static Path autosavePath() {
        return new File(AppDataDirectory.root(), "session/autosave.json").toPath();
    }
}
