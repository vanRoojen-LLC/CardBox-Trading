package com.cardpricer.service;

import com.cardpricer.gui.panel.PreferencesPanel;
import com.cardpricer.model.TradeRecord;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans {@code data/trades/*.txt} files and parses them into {@link TradeRecord} instances.
 * Also merges shared documents, keeping the latest revision of each trade.
 */
public class TradeHistoryService {

    private static final DateTimeFormatter FILENAME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    private static final Pattern P_CUSTOMER = Pattern.compile("^Customer Name:\\s*(.+)$");
    private static final Pattern P_TRADER   = Pattern.compile("^Trader Name:\\s*(.+)$");
    private static final Pattern P_TOTAL_V  = Pattern.compile("^Total Value:\\s*\\$([\\d.,]+)$");
    private static final Pattern P_TOTAL_C  = Pattern.compile("^Total Cards:\\s*(\\d+)$");
    private static final Pattern P_PAYMENT  = Pattern.compile("^Payment Method:\\s*(.+)$");

    /**
     * Loads all trade records from {@code localDirectory} (and the shared folder if configured).
     * Results are sorted newest-first.
     *
     * @param localDirectory  path to the local trades directory (e.g. "data/trades")
     */
    public static List<TradeRecord> loadAll(String localDirectory) {
        return loadAll(localDirectory,PreferencesPanel.getSharedTradesFolder());
    }

    static List<TradeRecord> loadAll(String localDirectory, String sharedPath) {
        try { return new Session(localDirectory,sharedPath).refresh().records(); }
        catch(Exception failure) { throw new IllegalStateException("Could not load trade history",failure); }
    }

    /** Small immutable list data; receipt bodies stay in SQLite until selected. */
    public record Snapshot(List<TradeRecord> records,Map<String,String> sources,Map<String,String> tokens,String notice) {
        public Snapshot { records=List.copyOf(records);sources=Map.copyOf(sources);tokens=Map.copyOf(tokens); }
        public String token(TradeRecord record) { return tokens.get(record.historyKey()); }
    }
    public record Filter(String query,String payment,int inventory) {
        public Filter { java.util.Objects.requireNonNull(query);java.util.Objects.requireNonNull(payment);if(inventory<0 || inventory>2) throw new IllegalArgumentException("Invalid inventory filter"); }
    }
    public record Cursor(String date,String key) {}
    public record Page(Snapshot snapshot,long count,long cards,BigDecimal total,Cursor next) {}

    /** Reuse one repository per History panel. All methods that access storage run off the EDT. */
    public static final class Session {
        private final Path local,shared;
        private final TradeRepository repository;
        private final HistoryIndex index;
        private int lastReadCount;

        public Session(String localDirectory,String sharedDirectory) throws Exception {
            local=Path.of(localDirectory).toAbsolutePath().normalize();
            shared=sharedDirectory==null || sharedDirectory.isBlank() ? null : Path.of(sharedDirectory).toAbsolutePath().normalize();
            repository=repository(localDirectory);index=new HistoryIndex(repository);
        }

        public Snapshot cached() throws Exception { return snapshot(""); }

        public Snapshot refresh() throws Exception { return snapshot(refreshIndex()); }

        public Page page(Filter filter,int limit,Cursor after,String notice) throws Exception {
            return HistoryPageQuery.load(repository,local,shared,filter,limit,after,notice);
        }

        public String refreshIndex() throws Exception {
            var deletionResult=new TradeDeletionService(repository,local,shared).sync();
            lastReadCount=0;
            lastReadCount+=index.scan(local,"receipt",repository.receiptNames(),false,file -> {
                var loaded=readReceipt(file);
                if(loaded.record().tradeId==null) repository.importLegacy(file);
                return loaded;
            }).reads();
            String notice=deletionResult.pending()>0 ? "Some deletions are waiting for file cleanup or shared sync." : "";
            if(shared!=null) {
                try {
                    if(!Files.isDirectory(shared)) throw new IOException("Shared folder unavailable");
                    if(!shared.equals(local)) lastReadCount+=index.scan(shared,"receipt",Set.of(),true,TradeHistoryService::readReceipt).reads();
                    lastReadCount+=index.scan(shared.resolve(SharedTradeService.DIRECTORY),"revision",Set.of(),false,SharedTradeService::historyFile).reads();
                } catch(IOException failure) {
                    notice="Shared history unavailable; showing saved history. Refresh to retry.";
                }
            }
            return notice;
        }

        int lastReadCount() { return lastReadCount; }

        private Snapshot snapshot(String notice) throws Exception {
            Map<String,HistoryIndex.Entry> selected=new LinkedHashMap<>();
            merge(selected,index.entries(HistoryIndex.scope(local),"receipt"),false);
            for(var r:repository.history(local))
                selected.put(r.historyKey(),new HistoryIndex.Entry(r.historyKey(),Long.toString(r.revision),r));
            if(shared!=null) {
                if(!shared.equals(local)) merge(selected,index.entries(HistoryIndex.scope(shared),"receipt"),false);
                merge(selected,index.entries(HistoryIndex.scope(shared.resolve(SharedTradeService.DIRECTORY)),"revision"),true);
            }
            var sources=new HashMap<String,String>();var tokens=new HashMap<String,String>();
            var records=new ArrayList<TradeRecord>();
            for(var e:selected.values()) {
                var r=e.record();records.add(r);sources.put(r.historyKey(),e.source());
                tokens.put(r.historyKey(),e.source()+"\n"+e.stamp()+"\n"+HistoryIndex.metadata(r));
            }
            records=new ArrayList<>(repository.inventoryStatuses(records));
            records.sort(Comparator.comparing((TradeRecord r)->r.date).reversed().thenComparing(TradeRecord::historyKey));
            return new Snapshot(records,sources,tokens,notice);
        }

        private static void merge(Map<String,HistoryIndex.Entry> selected,List<HistoryIndex.Entry> entries,boolean preferEqual) {
            for(var e:entries) selected.merge(e.record().historyKey(),e,(old,next) ->
                    next.record().revision>old.record().revision || (preferEqual && next.record().revision==old.record().revision) ? next : old);
        }

        public Set<String> search(Snapshot snapshot,String query) throws Exception {
            var sources=index.search(query);var keys=new HashSet<String>();
            snapshot.sources().forEach((key,source)-> { if(sources.contains(source)) keys.add(key); });
            return keys;
        }

        public String receipt(Snapshot snapshot,TradeRecord record) throws Exception {
            if(new TradeDeletionStore(repository).snapshot().contains(record)) throw new IOException("This trade was deleted");
            String source=snapshot.sources().get(record.historyKey());
            String body=source==null ? null : index.body(source,snapshot.token(record));
            if(body==null) throw new IOException("Receipt changed. Refresh History and select it again.");
            return body;
        }
    }

    static HistoryIndex.Loaded readReceipt(Path path) throws IOException {
        String content=Files.readString(path,StandardCharsets.UTF_8);
        return new HistoryIndex.Loaded(parseRecord(path.toFile(),content),content);
    }

    private static TradeRecord parseRecord(File file,String content) {
        LocalDateTime date = parseDateFromFilename(file);
        String customerName  = "Unknown";
        String traderName    = "Unknown";
        String paymentMethod = "Unknown";
        BigDecimal totalValue = BigDecimal.ZERO;
        int totalCards = 0;
        UUID tradeId = null;
        long revision = 0;

        {
            List<String> lines = content.lines().toList();
            for (String line : lines) {
                Matcher m;
                if (line.startsWith("Trade ID: ")) {
                    try { tradeId=UUID.fromString(line.substring(10).trim()); } catch (IllegalArgumentException ignored) {}
                } else if (line.startsWith("Revision: ")) {
                    try { revision=Long.parseLong(line.substring(10).trim()); } catch (NumberFormatException ignored) {}
                } else if ((m = P_CUSTOMER.matcher(line)).matches()) {
                    customerName = m.group(1).trim();
                } else if ((m = P_TRADER.matcher(line)).matches()) {
                    traderName = m.group(1).trim();
                } else if ((m = P_TOTAL_V.matcher(line)).matches()) {
                    try {
                        totalValue = new BigDecimal(m.group(1).replace(",", "").trim());
                    } catch (NumberFormatException ignored) {}
                } else if ((m = P_TOTAL_C.matcher(line)).matches()) {
                    try {
                        totalCards = Integer.parseInt(m.group(1).trim());
                    } catch (NumberFormatException ignored) {}
                } else if ((m = P_PAYMENT.matcher(line)).matches()) {
                    paymentMethod = m.group(1).trim();
                }
            }
        }

        // Early structured receipts omitted the revision in their text; companion JSON retained it.
        if (tradeId!=null && revision==0) {
            String name=file.getName();
            var snapshot=file.toPath().resolveSibling(name.substring(0,name.length()-4)+".json");
            try {
                var json=new org.json.JSONObject(Files.readString(snapshot,StandardCharsets.UTF_8));
                if (tradeId.toString().equals(json.getString("id"))) revision=json.getLong("revision");
            } catch (IOException | RuntimeException ignored) { /* Keep older receipts readable without JSON. */ }
        }

        return new TradeRecord(
                file.getAbsolutePath(),
                date,
                customerName,
                traderName,
                paymentMethod,
                totalValue,
                totalCards, tradeId, revision, false
        );
    }

    public static TradeRepository repository(String localDirectory) throws Exception {
        return new TradeRepository(java.nio.file.Path.of(localDirectory).resolveSibling("ledger").resolve("trades.sqlite"));
    }

    /** Companion import path for this exact receipt revision, in its local or shared folder. */
    public static java.nio.file.Path csvPath(TradeRecord record) {
        var receipt = java.nio.file.Path.of(record.filename);
        String name = receipt.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".txt"))
            throw new IllegalArgumentException("Expected a trade receipt filename");
        return receipt.resolveSibling(name.substring(0, name.length() - 4) + ".csv");
    }

    public static com.cardpricer.model.TradeDraft openForEditing(TradeRecord record, String localDirectory, String sharedPath) throws Exception {
        if (record.tradeId==null) throw new IllegalArgumentException("Use the receipt editor for this older trade");
        var repo=repository(localDirectory);
        repo.requireActive(record.tradeId);
        if (sharedPath!=null && !sharedPath.isBlank())
            return new SharedTradeService(repo,java.nio.file.Path.of(localDirectory),java.nio.file.Path.of(sharedPath)).open(record.tradeId,record.revision);
        if (!repo.sharedSource(record.tradeId).isBlank())
            throw new IOException("Reconnect this trade's Shared Trades Folder before editing.");
        var draft=repo.committedDraft(record.tradeId);
        if (draft==null) throw new IOException("Configure the Shared Trades Folder to open this trade on this workstation.");
        if (draft.revision()!=record.revision) throw new IOException("This trade changed. Refresh History and reopen it.");
        return draft;
    }

    /** Fall back to the committed receipt when an output folder is temporarily unavailable. */
    public static String receiptContent(TradeRecord record, String localDirectory) throws Exception {
        if (record.tradeId != null) {
            var repo=repository(localDirectory);
            var draft=repo.committedDraft(record.tradeId);
            if (draft!=null && draft.revision()==record.revision) return repo.receiptContent(record.tradeId);
            var parent=java.nio.file.Path.of(record.filename).toAbsolutePath().getParent();
            String receipt=SharedTradeService.receipt(parent,record.tradeId,record.revision);
            if (receipt!=null) return receipt;
        }
        return Files.readString(java.nio.file.Path.of(record.filename),StandardCharsets.UTF_8);
    }

    /** A legacy receipt can be corrected without inventing the missing card snapshots. */
    public static void saveLegacyReceipt(TradeRecord record, String content, String localDirectory) throws Exception {
        synchronized(TradeDeletionService.LOCAL_LOCK) {
            if (record.tradeId != null) throw new IllegalArgumentException("Use the trade editor for a structured trade");
            if (content.isBlank()) throw new IllegalArgumentException("The receipt cannot be empty");
            var repo=repository(localDirectory);
            if(new TradeDeletionStore(repo).snapshot().contains(record)) throw new IOException("This trade was deleted");
            var source=java.nio.file.Path.of(record.filename);
            repo.importLegacy(source);
            var destination=java.nio.file.Path.of(localDirectory).resolve(source.getFileName());
            if (Files.exists(destination) && !Files.isSameFile(source,destination)) repo.importLegacy(destination);
            com.cardpricer.util.AtomicFiles.write(destination,content);
            repo.setInventoried(record,false);
            InventoryStatusSyncService.requestSync();
        }
    }

    private static LocalDateTime parseDateFromFilename(File file) {
        String name = file.getName();
        // Expected prefix: yyyy-MM-dd_HH-mm-ss  (19 chars)
        if (name.length() >= 19) {
            try {
                return LocalDateTime.parse(name.substring(0, 19), FILENAME_FMT);
            } catch (Exception ignored) {}
        }
        // Fall back to last-modified timestamp
        return LocalDateTime.ofInstant(
                Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault());
    }
}
