package com.cardpricer.service;

import com.cardpricer.model.TradeDraft;
import com.cardpricer.model.InventoryStatusChange;
import com.cardpricer.util.AtomicFiles;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** Workstation-only SQLite ledger. Snapshot and output jobs commit in one transaction. */
public final class TradeRepository {
    public record Output(String name, String content) {}
    public record Job(long id, String tradeId, String name, String content, String hash, int attempts) {}
    private final Path database;
    private final java.util.function.Consumer<String> boundary;
    public TradeRepository(Path database) throws SQLException, IOException {
        this(database, ignored -> {});
    }
    TradeRepository(Path database, java.util.function.Consumer<String> boundary) throws SQLException, IOException {
        this.database = database.toAbsolutePath();
        this.boundary = boundary;
        Files.createDirectories(this.database.getParent());
        try (Connection connection = connect(); Statement s = connection.createStatement()) {
            int version;
            try (ResultSet rs = s.executeQuery("PRAGMA user_version")) { version = rs.getInt(1); }
            if (version > 6) throw new SQLException("Ledger was created by a newer application");
            if (version == 6) return;
            s.execute("PRAGMA journal_mode=WAL");
            s.execute("CREATE TABLE IF NOT EXISTS drafts(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, snapshot TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS trades(id TEXT PRIMARY KEY, approved_at TEXT NOT NULL, snapshot TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS settlements(trade_id TEXT PRIMARY KEY REFERENCES trades(id), snapshot TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS legacy_records(id TEXT PRIMARY KEY, filename TEXT NOT NULL, attachment BLOB NOT NULL, imported_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS jobs(id INTEGER PRIMARY KEY, trade_id TEXT NOT NULL REFERENCES trades(id), "
                    + "name TEXT NOT NULL, content TEXT NOT NULL, hash TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'pending', "
                    + "attempts INTEGER NOT NULL DEFAULT 0, error TEXT, UNIQUE(trade_id,name))");
            s.execute("CREATE TABLE IF NOT EXISTS trade_versions(trade_id TEXT NOT NULL, revision INTEGER NOT NULL, snapshot TEXT NOT NULL, archived_at TEXT NOT NULL, PRIMARY KEY(trade_id,revision))");
            s.execute("CREATE TABLE IF NOT EXISTS shared_trade_sources(trade_id TEXT PRIMARY KEY, folder TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS inventory_status(record_key TEXT PRIMARY KEY, revision INTEGER NOT NULL, inventoried INTEGER NOT NULL, updated_at TEXT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS inventory_changes(id TEXT PRIMARY KEY, record_key TEXT NOT NULL, revision INTEGER NOT NULL, sequence INTEGER NOT NULL, inventoried INTEGER NOT NULL, updated_at TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS inventory_changes_record ON inventory_changes(record_key,revision,sequence)");
            if (version < 3) {
                connection.setAutoCommit(false);
                try {
                    try (ResultSet rs=s.executeQuery("SELECT * FROM inventory_status")) {
                        while (rs.next()) {
                            String key=rs.getString("record_key"), at=rs.getString("updated_at");
                            long revision=rs.getLong("revision");boolean value=rs.getBoolean("inventoried");
                            UUID id=UUID.nameUUIDFromBytes((key+"\n"+revision+"\n"+value+"\n"+at).getBytes(StandardCharsets.UTF_8));
                            insertInventoryChange(connection,new InventoryStatusChange(id,key,revision,1,value,Instant.parse(at)));
                        }
                    }
                    s.execute("PRAGMA user_version=3");
                    connection.commit();
                } catch (Exception failure) { connection.rollback();throw failure; }
            }
            if (version < 4) {
                connection.setAutoCommit(false);
                try {
                    HistoryIndex.create(connection);
                    try (var rows=s.executeQuery("SELECT snapshot FROM trades")) {
                        while(rows.next()) indexHistory(connection,TradeDraft.fromJson(new JSONObject(rows.getString(1))));
                    }
                    s.execute("PRAGMA user_version=4");
                    connection.commit();
                } catch (Exception failure) { connection.rollback();throw failure; }
            }
            if(version<5) {
                connection.setAutoCommit(false);
                try {
                    TradeDeletionStore.create(connection);s.execute("PRAGMA user_version=5");connection.commit();
                } catch(Exception failure) { connection.rollback();throw failure; }
            }
            if(version<6) {
                connection.setAutoCommit(false);
                try {
                    if(!hasColumn(connection,"drafts","editing_revision")) s.execute("ALTER TABLE drafts ADD COLUMN editing_revision INTEGER");
                    if(!hasColumn(connection,"drafts","updated_at")) s.execute("ALTER TABLE drafts ADD COLUMN updated_at TEXT NOT NULL DEFAULT ''");
                    HistoryIndex.upgradeListing(connection);
                    s.execute("PRAGMA user_version=6");connection.commit();
                } catch(Exception failure) { connection.rollback();throw failure; }
            }
        }
    }
    Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA busy_timeout=5000"); s.execute("PRAGMA foreign_keys=ON"); s.execute("PRAGMA synchronous=FULL");
        }
        return connection;
    }
    public void saveDraft(TradeDraft draft) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("INSERT INTO drafts(id,revision,snapshot) SELECT ?,?,? WHERE NOT EXISTS (SELECT 1 FROM trades WHERE id=?) "
                + "ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,snapshot=excluded.snapshot WHERE excluded.revision>=drafts.revision")) {
            s.setString(1, draft.id().toString()); s.setLong(2, draft.revision()); s.setString(3, draft.toJson().toString());
            s.setString(4, draft.id().toString()); s.executeUpdate();
        }
    }
    private static boolean hasColumn(Connection c,String table,String column) throws SQLException {
        try(var s=c.createStatement();var rows=s.executeQuery("PRAGMA table_info("+table+")")) {
            while(rows.next()) if(column.equals(rows.getString("name"))) return true;
        }
        return false;
    }
    public record DraftSummary(UUID id,long revision,Long editingRevision,String customer,int lines,String savedAt) {
        @Override public String toString() {
            return (customer.isBlank() ? "Unnamed customer" : customer)+" — "+lines+" lines"
                    +(editingRevision==null ? "" : " — editing revision "+editingRevision)+" — "+savedAt;
        }
    }
    public record RecoveryDraft(TradeDraft draft,Long editingRevision) {}
    public void saveRecoveryDraft(TradeDraft draft,Long editingRevision) throws SQLException {
        String snapshot=draft.toJson().toString();
        try(var c=connect();var s=c.prepareStatement("INSERT INTO drafts(id,revision,snapshot,editing_revision,updated_at) "
                +"SELECT ?,?,?,?,? WHERE NOT EXISTS(SELECT 1 FROM trades WHERE id=? AND (? IS NULL OR snapshot=?)) "
                +"ON CONFLICT(id) DO UPDATE SET revision=excluded.revision,snapshot=excluded.snapshot,editing_revision=excluded.editing_revision,updated_at=excluded.updated_at "
                +"WHERE excluded.revision>=drafts.revision")) {
            s.setString(1,draft.id().toString());s.setLong(2,draft.revision());s.setString(3,snapshot);
            s.setObject(4,editingRevision);s.setString(5,Instant.now().toString());s.setString(6,draft.id().toString());
            s.setObject(7,editingRevision);s.setString(8,snapshot);s.executeUpdate();
        }
    }
    public List<DraftSummary> recoveryDrafts() throws SQLException {
        var result=new ArrayList<DraftSummary>();
        try(var c=connect();var s=c.createStatement();var rows=s.executeQuery("SELECT id,revision,editing_revision,updated_at,"
                +"json_extract(snapshot,'$.customer'),json_array_length(snapshot,'$.lines') FROM drafts ORDER BY updated_at DESC,id")) {
            while(rows.next()) result.add(new DraftSummary(UUID.fromString(rows.getString(1)),rows.getLong(2),
                    rows.getObject(3)==null ? null : rows.getLong(3),rows.getString(5),rows.getInt(6),rows.getString(4)));
        }
        return result;
    }
    public RecoveryDraft recoveryDraft(UUID id) throws SQLException {
        var result=findRecoveryDraft(id);
        if(result==null) throw new SQLException("This draft is no longer available");
        return result;
    }
    public RecoveryDraft findRecoveryDraft(UUID id) throws SQLException {
        try(var c=connect();var s=c.prepareStatement("SELECT snapshot,editing_revision FROM drafts WHERE id=?")) {
            s.setString(1,id.toString());try(var rows=s.executeQuery()) {
                if(!rows.next()) return null;
                return new RecoveryDraft(TradeDraft.fromJson(new JSONObject(rows.getString(1))),rows.getObject(2)==null ? null : rows.getLong(2));
            }
        }
    }
    public void deleteDraft(UUID id) throws SQLException {
        try (Connection c=connect();PreparedStatement s=c.prepareStatement("DELETE FROM drafts WHERE id=?")) {
            s.setString(1,id.toString());s.executeUpdate();
        }
    }
    public boolean isCommitted(UUID id) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("SELECT 1 FROM trades WHERE id=?")) {
            s.setString(1, id.toString()); try (ResultSet rs = s.executeQuery()) { return rs.next(); }
        }
    }
    public void requireActive(UUID id) throws SQLException {
        try(var c=connect()) { requireActive(c,id); }
    }
    private static void requireActive(Connection c,UUID id) throws SQLException {
        if(TradeDeletionStore.contains(c,"trade:"+id,"")) throw new SQLException("This trade was deleted and cannot be restored or edited");
    }
    UUID tradeForOutput(String name) throws SQLException {
        try(var c=connect();var s=c.prepareStatement("SELECT trade_id FROM jobs WHERE name=?")) {
            s.setString(1,name);try(var rows=s.executeQuery()) { if(rows.next()) return UUID.fromString(rows.getString(1)); }
        }
        String receipt=name.substring(0,name.lastIndexOf('.'))+".txt";
        try(var c=connect();var s=c.createStatement();var rows=s.executeQuery("SELECT metadata FROM history_entries")) {
            while(rows.next()) {
                var metadata=new JSONObject(rows.getString(1));String id=metadata.getString("id");
                if(!id.isEmpty() && Path.of(metadata.getString("filename")).getFileName().toString().equalsIgnoreCase(receipt)) return UUID.fromString(id);
            }
        }
        return null;
    }
    public TradeDraft committedDraft(UUID id) throws SQLException {
        try (Connection c=connect(); PreparedStatement s=c.prepareStatement("SELECT snapshot FROM trades WHERE id=?")) {
            s.setString(1,id.toString());
            try (ResultSet rs=s.executeQuery()) { return rs.next() ? TradeDraft.fromJson(new JSONObject(rs.getString(1))) : null; }
        }
    }
    public String sharedSource(UUID id) throws SQLException {
        try (Connection c=connect(); PreparedStatement s=c.prepareStatement("SELECT folder FROM shared_trade_sources WHERE trade_id=?")) {
            s.setString(1,id.toString());
            try (ResultSet rs=s.executeQuery()) { return rs.next() ? rs.getString(1) : ""; }
        }
    }
    public void bindSharedSource(UUID id, Path folder) throws SQLException {
        try (Connection c=connect(); PreparedStatement s=c.prepareStatement("INSERT INTO shared_trade_sources VALUES(?,?) ON CONFLICT(trade_id) DO UPDATE SET folder=excluded.folder")) {
            s.setString(1,id.toString());s.setString(2,folder.toAbsolutePath().normalize().toString());s.executeUpdate();
        }
    }
    public List<TradeDraft> versions(UUID id) throws SQLException {
        List<TradeDraft> result=new ArrayList<>();
        try (Connection c=connect(); PreparedStatement s=c.prepareStatement("SELECT snapshot FROM trade_versions WHERE trade_id=? ORDER BY revision")) {
            s.setString(1,id.toString());
            try (ResultSet rs=s.executeQuery()) { while (rs.next()) result.add(TradeDraft.fromJson(new JSONObject(rs.getString(1)))); }
        }
        var current=committedDraft(id);
        if (current!=null) result.add(current);
        return result;
    }
    public void importLegacy(Path file) throws SQLException, IOException {
        byte[] attachment=Files.readAllBytes(file);
        try (Connection c=connect(); PreparedStatement s=c.prepareStatement("INSERT OR IGNORE INTO legacy_records VALUES(?,?,?,?)")) {
            s.setString(1,AtomicFiles.hash(attachment));s.setString(2,file.getFileName().toString());s.setBytes(3,attachment);
            s.setString(4,Instant.now().toString());s.executeUpdate();
        }
    }
    public List<com.cardpricer.model.TradeRecord> history(Path outputDirectory) throws SQLException {
        var records=new ArrayList<com.cardpricer.model.TradeRecord>();
        for(var entry:new HistoryIndex(this).entries("","managed")) {
            var r=entry.record();
            records.add(new com.cardpricer.model.TradeRecord(outputDirectory.toAbsolutePath().resolve(r.filename).toString(),
                    r.date,r.customerName,r.traderName,r.paymentMethod,r.totalValue,r.totalCards,r.tradeId,r.revision,false));
        }
        records.sort(Comparator.comparing((com.cardpricer.model.TradeRecord r)->r.date).reversed());
        return records;
    }

    Set<String> receiptNames() throws SQLException {
        var names=new HashSet<String>();
        try(var c=connect();var s=c.createStatement();var rows=s.executeQuery("SELECT name FROM jobs WHERE name LIKE '%.txt'")) {
            while(rows.next()) names.add(rows.getString(1));
        }
        return names;
    }

    /** Store the projection with the approved snapshot and jobs, including during migration. */
    private static void indexHistory(Connection c,TradeDraft draft) throws SQLException {
        String name="trade_"+draft.id()+".txt", body=null, approved;
        try(var s=c.prepareStatement("SELECT approved_at FROM trades WHERE id=?")) {
            s.setString(1,draft.id().toString());try(var rows=s.executeQuery()) { rows.next();approved=rows.getString(1); }
        }
        try(var s=c.prepareStatement("SELECT name,content FROM jobs WHERE trade_id=? AND name LIKE '%.txt' ORDER BY id DESC LIMIT 1")) {
            s.setString(1,draft.id().toString());try(var rows=s.executeQuery()) {
                if(rows.next()) { name=rows.getString(1);body=rows.getString(2); }
            }
        }
        var settlement=draft.settle();
        if(body==null) body=TradeApplicationService.receipt(draft,settlement);
        var record=new com.cardpricer.model.TradeRecord(name,
                java.time.LocalDateTime.ofInstant(Instant.parse(approved),java.time.ZoneId.systemDefault()),
                draft.customer(),draft.trader(),draft.payment(),settlement.market(),
                draft.lines().stream().mapToInt(line->line.quantity()).sum(),draft.id(),draft.revision(),false);
        HistoryIndex.put(c,record.historyKey(),"","managed",Long.toString(draft.revision()),new HistoryIndex.Loaded(record,body));
    }
    public void commit(TradeDraft draft, List<Output> outputs) throws SQLException {
        save(draft, outputs, null);
    }

    public void revise(TradeDraft draft, long expectedRevision, List<Output> outputs) throws SQLException {
        if (draft.revision() <= expectedRevision) throw new IllegalArgumentException("A correction must have a newer revision");
        save(draft, outputs, expectedRevision);
    }

    private void save(TradeDraft draft, List<Output> outputs, Long expectedRevision) throws SQLException {
        synchronized(TradeDeletionService.LOCAL_LOCK) {
            draft.validateForApproval(); // Validate before writing anything.
            String snapshot = draft.toJson().toString();
            try (Connection c = connect()) {
                c.setAutoCommit(false);
                try {
                    requireActive(c,draft.id());
                    try (PreparedStatement s = c.prepareStatement("SELECT snapshot FROM trades WHERE id=?")) {
                        s.setString(1, draft.id().toString());
                        try (ResultSet rs = s.executeQuery()) {
                            if (rs.next()) {
                                String previous=rs.getString(1);
                                if (new JSONObject(previous).similar(new JSONObject(snapshot))) { c.rollback(); return; }
                                if (expectedRevision == null)
                                    throw new SQLException("This trade is already approved; retry its pending outputs");
                                TradeDraft old=TradeDraft.fromJson(new JSONObject(previous));
                                if (old.revision()!=expectedRevision) throw new SQLException("This trade changed since it was opened. Reopen it from History before editing.");
                                try (PreparedStatement archive=c.prepareStatement("INSERT INTO trade_versions VALUES(?,?,?,?)")) {
                                    archive.setString(1,draft.id().toString());archive.setLong(2,old.revision());
                                    archive.setString(3,previous);archive.setString(4,Instant.now().toString());archive.executeUpdate();
                                }
                            } else if (expectedRevision != null) {
                                throw new SQLException("The original trade could not be found");
                            }
                        }
                    }
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO trades VALUES(?,?,?) ON CONFLICT(id) DO UPDATE SET snapshot=excluded.snapshot")) {
                        s.setString(1, draft.id().toString()); s.setString(2, Instant.now().toString()); s.setString(3, snapshot); s.executeUpdate();
                    }
                    var settled=draft.settle();
                    var allocations=new org.json.JSONArray();
                    for (var allocation:settled.lines()) allocations.put(new JSONObject().put("lineId",allocation.line().id())
                            .put("credit",allocation.credit().toPlainString()).put("check",allocation.check().toPlainString())
                            .put("cost",allocation.cost().toPlainString()).put("stock",allocation.line().stock()));
                    var settlementJson=new JSONObject().put("schema",1).put("applicationVersion",com.cardpricer.util.AppVersion.CURRENT)
                            .put("credit",settled.credit().toPlainString()).put("check",settled.check().toPlainString()).put("allocations",allocations);
                    try (PreparedStatement s=c.prepareStatement("INSERT INTO settlements VALUES(?,?) ON CONFLICT(trade_id) DO UPDATE SET snapshot=excluded.snapshot")) {
                        s.setString(1,draft.id().toString());s.setString(2,settlementJson.toString());s.executeUpdate();
                    }
                    for (Output output : outputs) {
                        try (PreparedStatement s = c.prepareStatement("INSERT INTO jobs(trade_id,name,content,hash) VALUES(?,?,?,?)")) {
                            s.setString(1, draft.id().toString()); s.setString(2, output.name()); s.setString(3, output.content());
                            s.setString(4, AtomicFiles.hash(output.content().getBytes(StandardCharsets.UTF_8))); s.executeUpdate();
                        }
                    }
                    // An imported/competing revision must not discard different local edits.
                    try (PreparedStatement s = c.prepareStatement("DELETE FROM drafts WHERE id=? AND snapshot=?")) {
                        s.setString(1, draft.id().toString());s.setString(2,snapshot);s.executeUpdate();
                    }
                    indexHistory(c,draft);
                    boundary.accept("beforeCommit");
                    c.commit();
                    boundary.accept("afterCommit");
                } catch (Exception failure) { c.rollback(); throw failure; }
            }
        }
    }
    /** Status belongs to a particular revision: a correction needs POS review again. */
    public void setInventoried(com.cardpricer.model.TradeRecord record, boolean value) throws SQLException {
        // The SELECT and INSERT are one SQLite statement, so simultaneous local edits serialize.
        try (Connection c=connect(); PreparedStatement s=c.prepareStatement("INSERT INTO inventory_changes "
                + "SELECT ?,?,?,COALESCE(MAX(sequence),0)+1,?,? FROM inventory_changes WHERE record_key=? AND revision=?")) {
            s.setString(1,UUID.randomUUID().toString());s.setString(2,record.historyKey());s.setLong(3,record.revision);
            s.setBoolean(4,value);s.setString(5,Instant.now().toString());s.setString(6,record.historyKey());
            s.setLong(7,record.revision);s.executeUpdate();
        }
    }

    public List<com.cardpricer.model.TradeRecord> inventoryStatuses(List<com.cardpricer.model.TradeRecord> records) throws SQLException {
        Map<String,InventoryStatusChange> statuses=new HashMap<>();
        for (var change:inventoryChanges()) statuses.merge(change.recordKey(),change,
                (a,b) -> InventoryStatusChange.ORDER.compare(a,b)>=0 ? a : b);
        return records.stream().map(record -> {
            var status=statuses.get(record.historyKey());
            return record.withInventoried(status!=null && status.revision()==record.revision && status.inventoried());
        }).toList();
    }

    public List<InventoryStatusChange> inventoryChanges() throws SQLException {
        List<InventoryStatusChange> changes=new ArrayList<>();
        try (Connection c=connect(); Statement s=c.createStatement();ResultSet rs=s.executeQuery("SELECT * FROM inventory_changes")) {
            while(rs.next()) changes.add(new InventoryStatusChange(UUID.fromString(rs.getString("id")),rs.getString("record_key"),
                    rs.getLong("revision"),rs.getLong("sequence"),rs.getBoolean("inventoried"),Instant.parse(rs.getString("updated_at"))));
        }
        return changes;
    }

    public boolean importInventoryChange(InventoryStatusChange change) throws SQLException {
        try (Connection c=connect()) { return insertInventoryChange(c,change); }
    }

    private static boolean insertInventoryChange(Connection c,InventoryStatusChange change) throws SQLException {
        try (PreparedStatement s=c.prepareStatement("INSERT OR IGNORE INTO inventory_changes VALUES(?,?,?,?,?,?)")) {
            s.setString(1,change.id().toString());s.setString(2,change.recordKey());s.setLong(3,change.revision());
            s.setLong(4,change.sequence());s.setBoolean(5,change.inventoried());s.setString(6,change.updatedAt().toString());
            return s.executeUpdate()!=0;
        }
    }

    public String receiptContent(UUID tradeId) throws SQLException {
        try (Connection c=connect(); PreparedStatement s=c.prepareStatement("SELECT content FROM jobs WHERE trade_id=? AND name LIKE '%.txt' ORDER BY id DESC LIMIT 1")) {
            s.setString(1,tradeId.toString());
            try (ResultSet rs=s.executeQuery()) { return rs.next() ? rs.getString(1) : ""; }
        }
    }
    public List<Job> pending() throws SQLException {
        List<Job> jobs = new ArrayList<>();
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT * FROM jobs WHERE status NOT IN ('done','deleted') ORDER BY id")) {
            while (rs.next()) jobs.add(new Job(rs.getLong("id"), rs.getString("trade_id"), rs.getString("name"),
                    rs.getString("content"), rs.getString("hash"), rs.getInt("attempts")));
        }
        return jobs;
    }
    public void recordAttempt(long id, String error) throws SQLException {
        try (Connection c = connect(); PreparedStatement s = c.prepareStatement("UPDATE jobs SET status=?,attempts=attempts+1,error=? WHERE id=? AND status!='deleted'")) {
            s.setString(1, error == null ? "done" : "pending"); s.setString(2, error); s.setLong(3, id); s.executeUpdate();
        }
    }
    public int retryOutputs(Path destination) throws SQLException {
        synchronized(TradeDeletionService.LOCAL_LOCK) {
            int failed = 0;
            for (Job job : pending()) {
                if(new TradeDeletionStore(this).contains("trade:"+job.tradeId(),job.name())) continue;
                try {
                    Path root = destination.toAbsolutePath().normalize();
                    Path file = root.resolve(job.name()).normalize();
                    if (!file.getParent().equals(root)) throw new IOException("Invalid output name");
                    if (Files.exists(file)) {
                        if (!AtomicFiles.hash(Files.readAllBytes(file)).equals(job.hash())) throw new IOException("Existing output has different contents");
                    } else AtomicFiles.write(file, job.content());
                    boundary.accept("afterOutputWrite");
                    recordAttempt(job.id(), null);
                } catch (IOException e) { recordAttempt(job.id(), e.getMessage()); failed++; }
            }
            return failed;
        }
    }
}
