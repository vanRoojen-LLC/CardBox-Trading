package com.cardpricer.service;

import com.cardpricer.model.Card;
import com.cardpricer.util.AppDataDirectory;
import com.cardpricer.util.VintageUtil;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.io.PushbackInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Singleton service that maintains a local catalog of Scryfall cards for
 * instant lookup without per-card API calls.
 *
 * <p>The catalog is built from Scryfall's {@code default_cards} bulk-data file
 * (every English printing). After the first download the data is saved as a
 * compact NDJSON.gz file (~15–25 MB) in the application cache directory and
 * reloaded on subsequent launches without another network call.
 *
 * <p><b>Index key format:</b> {@code "SETCODE:COLLNUM"} — both components are
 * upper-case; special characters (★) are preserved in the collector number;
 * hyphens are preserved (required for PLST composites, e.g. {@code "PLST:ARB-1"}).
 * Vintage set aliases (e.g. {@code "alpha"} → {@code "lea"}) are resolved via
 * {@link VintageUtil#resolveSetAlias(String)} so that user input always maps to
 * the canonical Scryfall set code.
 */
public class ScryfallCatalogService implements SearchableCardRepository {

    // ── Public interface ──────────────────────────────────────────────────────

    /**
     * Progress callback for long-running download/build operations.
     * All methods are called on the background thread — implementations must
     * dispatch EDT updates via {@code SwingUtilities.invokeLater}.
     */
    public interface DownloadProgress {
        /**
         * Called periodically during download and build.
         *
         * @param cardsProcessed number of cards indexed so far
         * @param phase          human-readable description of the current phase
         */
        void onUpdate(int cardsProcessed, String phase);

        /** Returns {@code true} if the user has requested cancellation. */
        boolean isCancelled();
    }

    // ── Singleton ────────────────────────────────────────────────────────────

    private static final ScryfallCatalogService INSTANCE = new ScryfallCatalogService();

    private ScryfallCatalogService() {}

    /** Returns the singleton instance. */
    public static ScryfallCatalogService getInstance() { return INSTANCE; }

    // ── Constants ─────────────────────────────────────────────────────────────

    private static final String BULK_DATA_API  = "https://api.scryfall.com/bulk-data";
    private static final String CACHE_FILENAME = "catalog.ndjson.gz";

    // ── State ─────────────────────────────────────────────────────────────────

    /** Populated after a successful load or build; {@code null} when not loaded. */
    private record Snapshot(Map<String,com.cardpricer.model.ProviderPrinting> codes,CardNameIndex names,int ambiguous,long generation) {}
    private volatile Snapshot index;
    private final java.util.concurrent.atomic.AtomicLong generations=new java.util.concurrent.atomic.AtomicLong();
    public int getAmbiguousLegacyKeys() { var snapshot=index;return snapshot==null ? 0 : snapshot.ambiguous(); }
    @Override public long generation() { var snapshot=index;return snapshot==null ? generations.get() : snapshot.generation(); }

    // ── Public accessors ──────────────────────────────────────────────────────

    /** Returns {@code true} if the local NDJSON.gz cache file exists on disk. */
    public boolean isCatalogAvailable() { return getCacheFile().exists(); }

    /**
     * Returns the age of the cache file in milliseconds,
     * or {@link Long#MAX_VALUE} if the cache does not exist.
     */
    public long getCacheAgeMs() {
        File f = getCacheFile();
        return f.exists() ? System.currentTimeMillis() - f.lastModified() : Long.MAX_VALUE;
    }

    /** Returns the number of cards in the currently loaded index, or 0 if not loaded. */
    public int getCardCount() { var snapshot=index;return snapshot==null ? 0 : snapshot.codes().size(); }

    /** Returns {@code true} if the index is loaded into memory and ready for lookup. */
    public boolean isLoaded() { return index != null; }

    /** Clears the in-memory index without deleting the cache file. */
    public void invalidate() {
        generations.incrementAndGet();
        index = null;
    }

    /**
     * Looks up a card by set code and collector number.
     *
     * <p>The set code is resolved through the vintage alias table before the
     * lookup, so {@code "alpha"} and {@code "lea"} both find Alpha cards.
     *
     * @param setCode         Scryfall or user-facing set code (any case)
     * @param collectorNumber exact provider collector number, including markers (e.g. {@code "73★"})
     * @return the matching {@link Card}, or {@link Optional#empty()} if not in the catalog
     */
    public Optional<Card> lookup(String setCode, String collectorNumber) {
        var snapshot = index;
        if (snapshot == null) return Optional.empty();
        return Optional.ofNullable(snapshot.codes().get(buildKey(setCode, collectorNumber))).map(com.cardpricer.model.ProviderPrinting::toCard);
    }
    public CardNameIndex.Matches searchNames(String query,String set,int limit) {
        var snapshot=index;
        return snapshot==null ? new CardNameIndex.Matches(List.of(),0) : snapshot.names().search(query,set,limit);
    }

    // ── Key normalisation ─────────────────────────────────────────────────────

    /**
     * Builds a lookup key: {@code "SETCODE:COLLNUM"} — both upper-case;
     * collector markers and hyphens are preserved.
     */
    private static String buildKey(String setCode, String collectorNumber) {
        // Resolve vintage aliases ("alpha" → "lea") to match how fetchCard() works
        String resolved  = VintageUtil.resolveSetAlias(setCode);
        String cleanColl = collectorNumber.toUpperCase(java.util.Locale.ROOT);
        return resolved.toUpperCase(java.util.Locale.ROOT) + ":" + cleanColl;
    }

    // ── Cache file path ───────────────────────────────────────────────────────

    private static File getCacheFile() {
        return new File(AppDataDirectory.cache(), CACHE_FILENAME);
    }

    // ── Load from local cache ─────────────────────────────────────────────────

    /**
     * Loads the catalog from the local NDJSON.gz cache into memory.
     *
     * @return the number of cards loaded
     * @throws IOException if the cache file does not exist or cannot be read
     */
    public synchronized int loadFromDisk() throws IOException {
        File cacheFile = getCacheFile();
        if (!cacheFile.exists()) {
            throw new IOException("Catalog cache not found: " + cacheFile.getAbsolutePath());
        }

        Map<String, com.cardpricer.model.ProviderPrinting> newIndex = new HashMap<>(400_000);
        java.util.Set<String> ambiguous = new java.util.HashSet<>();
        java.util.Set<String> legacyKeys = new java.util.HashSet<>();
        int count = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new GZIPInputStream(new FileInputStream(cacheFile)), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    JSONObject obj = new JSONObject(line);
                    Card card = cardFromCacheLine(obj);
                    String key = buildKey(card.getSetCode(), card.getCollectorNumber());
                    boolean legacy = !obj.has("name");
                    if (ambiguous.contains(key)) {
                        if (!legacy) throw new IOException("Mixed legacy and current catalog key");
                        continue;
                    }
                    var printing = com.cardpricer.model.ProviderPrinting.from(card);
                    var previous = newIndex.putIfAbsent(key, printing);
                    if (previous != null) {
                        if (!legacy || !legacyKeys.contains(key)) throw new IOException("Duplicate catalog printing key");
                        // Old cache files collapsed PLST identifiers. Never guess which printing won.
                        if (!previous.equals(printing)) { newIndex.remove(key); ambiguous.add(key); }
                        continue;
                    }
                    if (legacy) legacyKeys.add(key);
                    count++;
                } catch (Exception failure) {
                    throw new IOException("Invalid catalog record", failure);
                }
            }
        }

        if (newIndex.isEmpty()) throw new IOException("Catalog is empty; previous cache retained");
        var names=new CardNameIndex(newIndex.values());
        this.index=new Snapshot(Collections.unmodifiableMap(newIndex),names,ambiguous.size(),generations.incrementAndGet());
        return getCardCount();
    }

    /** Deserialises a {@link Card} from a compact cache-line JSON object. */
    private static Card cardFromCacheLine(JSONObject obj) {
        if (obj.has("name")) return ProviderCardMapper.fromJson(obj);
        Card card = new Card();
        card.setName(obj.getString("nm"));
        card.setSetCode(obj.getString("s"));
        card.setCollectorNumber(obj.getString("n"));
        card.setRarity(obj.optString("r", "common"));

        // Prices: absent key → null → Card.setPrice(null) stores "N/A"
        card.setPrice(    obj.has("p")  ? obj.getString("p")  : null);
        card.setFoilPrice(obj.has("fp") ? obj.getString("fp") : null);
        card.setEtchedPrice(obj.has("ep") ? obj.getString("ep") : null);

        card.setReserved(obj.optBoolean("rl", false));

        if (obj.has("fx")) {
            JSONArray fxArr = obj.getJSONArray("fx");
            List<String> fx = new ArrayList<>(fxArr.length());
            for (int i = 0; i < fxArr.length(); i++) fx.add(fxArr.getString(i));
            card.setFrameEffects(fx);
        }
        if (obj.has("i")) card.setImageUrl(obj.getString("i"));
        return card;
    }

    // ── Download and build ────────────────────────────────────────────────────

    /**
     * Downloads the Scryfall {@code default_cards} bulk file, parses it while
     * streaming input (the in-memory index still requires heap space), writes a compact NDJSON.gz cache to disk, and
     * loads the result into the in-memory index.
     *
     * <p>This method is synchronous and should be called from a background thread
     * (e.g. a {@link javax.swing.SwingWorker}).
     *
     * @param progress optional progress / cancellation callback; may be {@code null}
     * @throws InterruptedException if cancelled via the progress callback
     * @throws Exception            on any network or I/O failure
     */
    private long lastRefreshCompleted;
    public void downloadAndBuild(DownloadProgress progress) throws Exception {
        long requestedAt = System.nanoTime();
        synchronized (this) {
            if (Thread.currentThread().isInterrupted() || (progress != null && progress.isCancelled()))
                throw new InterruptedException("Catalog refresh cancelled");
            if (lastRefreshCompleted >= requestedAt) {
                if (progress != null) progress.onUpdate(getCardCount(), "Catalog already refreshed");
                return;
            }
            downloadAndBuildSingle(progress);
            lastRefreshCompleted = System.nanoTime();
        }
    }

    private void downloadAndBuildSingle(DownloadProgress progress) throws Exception {
        // Step 1 — find the download URI in Scryfall's bulk-data catalogue
        if (progress != null) {
            if (progress.isCancelled()) throw new InterruptedException("Catalog refresh cancelled");
            progress.onUpdate(0, "Looking up Scryfall bulk data URL\u2026");
        }

        JSONObject bulkMeta   = fetchBulkDataMeta();
        // Prefer the new JSONL field; fall back to the legacy array field if absent
        String     downloadUrl = bulkMeta.has("jsonl_download_uri")
                ? bulkMeta.getString("jsonl_download_uri")
                : bulkMeta.getString("download_uri");

        if (progress != null) {
            if (progress.isCancelled()) throw new InterruptedException("Catalog refresh cancelled");
            progress.onUpdate(0, "Connecting to Scryfall\u2026");
        }

        // Step 2 — stream, parse, and write cache simultaneously
        File cacheFile = getCacheFile();
        File tmpFile = java.nio.file.Files.createTempFile(cacheFile.toPath().getParent(), "catalog-", ".tmp").toFile();
        try {
        Map<String, com.cardpricer.model.ProviderPrinting> newIndex = new HashMap<>(400_000);

        HttpURLConnection conn = null;
        try {
            conn = openConnection(downloadUrl);

            try (InputStream       httpIn     = conn.getInputStream();
                 InputStream       streamIn   = maybeWrapGzip(httpIn);
                 BufferedReader    reader     = new BufferedReader(
                         new InputStreamReader(streamIn, StandardCharsets.UTF_8), 65_536);
                 FileOutputStream  fos        = new FileOutputStream(tmpFile);
                 GZIPOutputStream  gzOut      = new GZIPOutputStream(new BufferedOutputStream(fos));
                 PrintWriter       cacheWriter = new PrintWriter(
                         new OutputStreamWriter(gzOut, StandardCharsets.UTF_8))) {

                int[] cardsProcessed = {0};
                ProviderCardStream.read(reader, () -> Thread.currentThread().isInterrupted()
                        || (progress != null && progress.isCancelled()), cardJson -> {
                    if ("en".equals(cardJson.optString("lang")) && !cardJson.optBoolean("digital", false)) {
                        cardJson.put("price_observed_at", bulkMeta.optString("updated_at", "unknown"));
                        processCardJson(cardJson, newIndex, cacheWriter);
                        cardsProcessed[0]++;
                        if (cardsProcessed[0] % 5_000 == 0 && progress != null)
                            progress.onUpdate(cardsProcessed[0], "Parsing cards…");
                    }
                });

                cacheWriter.flush();
                if (cacheWriter.checkError()) throw new IOException("Catalog write failed");
                if (progress != null) {
                    progress.onUpdate(cardsProcessed[0], "Saving catalog to disk\u2026");
                }
            }
        } finally {
            if (conn != null) conn.disconnect();
        }

        if (newIndex.isEmpty()) throw new IOException("Empty catalog; previous cache retained");
        if (progress != null && progress.isCancelled()) throw new InterruptedException("Catalog refresh cancelled");
        // Read through gzip EOF to verify the completed footer and every serialized record.
        int verified=0;
        try (var verify=new BufferedReader(new InputStreamReader(new GZIPInputStream(new FileInputStream(tmpFile)),StandardCharsets.UTF_8))) {
            String record;
            while ((record=verify.readLine())!=null) { ProviderCardMapper.fromJson(new JSONObject(record)); verified++; }
        }
        if (verified!=newIndex.size()) throw new IOException("Catalog count mismatch; previous cache retained");
        var names=new CardNameIndex(newIndex.values());
        if(Thread.currentThread().isInterrupted() || (progress!=null && progress.isCancelled())) throw new InterruptedException("Catalog refresh cancelled");
        com.cardpricer.util.AtomicFiles.replace(tmpFile.toPath(), cacheFile.toPath());

        this.index=new Snapshot(Collections.unmodifiableMap(newIndex),names,0,generations.incrementAndGet());

        if (progress != null) {
            progress.onUpdate(getCardCount(),
                    "Done \u2014 " + getCardCount() + " cards indexed.");
        }
        } finally {
            java.nio.file.Files.deleteIfExists(tmpFile.toPath());
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Calls the Scryfall bulk-data API and returns the metadata object for
     * the {@code default_cards} type.
     */
    private JSONObject fetchBulkDataMeta() throws Exception {
        HttpURLConnection conn = openConnection(BULK_DATA_API);
        try {
            if (conn.getResponseCode() != 200) {
                throw new IOException("Scryfall bulk-data API returned HTTP "
                        + conn.getResponseCode());
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(
                    conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            JSONObject response = new JSONObject(sb.toString());
            JSONArray  items    = response.getJSONArray("data");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                if ("default_cards".equals(item.optString("type"))) return item;
            }
            throw new IOException("'default_cards' type not found in Scryfall bulk-data response");
        } finally {
            conn.disconnect();
        }
    }

    /**
     * Peeks at the first two bytes of the stream to detect the gzip magic number
     * (0x1f 0x8b) and wraps with {@link GZIPInputStream} if present.
     * Returns the stream unchanged if the content is not gzip-compressed.
     * This handles Scryfall serving bulk files as plain JSON vs. gzip transparently.
     */
    private static InputStream maybeWrapGzip(InputStream in) throws IOException {
        PushbackInputStream pb = new PushbackInputStream(in, 2);
        byte[] magic = new byte[2];
        int read = pb.read(magic, 0, 2);
        if (read > 0) pb.unread(magic, 0, read);
        if (read == 2 && magic[0] == (byte) 0x1f && magic[1] == (byte) 0x8b) {
            return new GZIPInputStream(pb, 65_536);
        }
        return pb;
    }

    private static HttpURLConnection openConnection(String url) throws Exception {
        HttpURLConnection conn = ProviderRequests.open(url);

        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        return conn;
    }

    /**
     * Parses one card JSON object from the Scryfall bulk array, adds it to the
     * in-memory index, and appends a compact line to the NDJSON.gz cache writer.
     *
     * Exact provider set and collector identifiers are retained, including PLST composites.
     */
    private static void processCardJson(JSONObject json, Map<String, com.cardpricer.model.ProviderPrinting> index, PrintWriter cacheWriter) {
        Card card = ProviderCardMapper.fromJson(json);
        String key = buildKey(card.getSetCode(), card.getCollectorNumber());
        var previous = index.putIfAbsent(key, com.cardpricer.model.ProviderPrinting.from(card));
        if (previous != null && !previous.identity().providerId().equals(card.getProviderId()))
            throw new IllegalArgumentException("Ambiguous printing identity: " + key);
        if (previous == null) cacheWriter.println(ProviderCardMapper.toJson(card));
    }
}
