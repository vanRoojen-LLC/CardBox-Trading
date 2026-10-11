package com.cardpricer.service;

import com.cardpricer.model.TradeRecord;
import org.json.JSONObject;
import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Local, rebuildable history projections. Receipt bodies are read only for indexing or selection. */
final class HistoryIndex {
    record Entry(String source, String stamp, TradeRecord record) {}
    record Loaded(TradeRecord record, String body) {}
    record Scan(int reads, boolean available) {}
    interface Loader { Loaded read(Path file) throws Exception; }
    private final TradeRepository repository;
    HistoryIndex(TradeRepository repository) { this.repository=repository; }

    static void create(Connection c) throws SQLException {
        try (var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS history_entries(source TEXT PRIMARY KEY,scope TEXT NOT NULL,kind TEXT NOT NULL,stamp TEXT NOT NULL,metadata TEXT NOT NULL,body TEXT NOT NULL,search_text TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS history_scope ON history_entries(scope,kind)");
            s.execute("CREATE VIRTUAL TABLE IF NOT EXISTS history_search USING fts5(search_text, content='history_entries',content_rowid='rowid',tokenize='trigram')");
            s.execute("CREATE TRIGGER IF NOT EXISTS history_insert AFTER INSERT ON history_entries BEGIN INSERT INTO history_search(rowid,search_text) VALUES(new.rowid,new.search_text); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS history_delete AFTER DELETE ON history_entries BEGIN INSERT INTO history_search(history_search,rowid,search_text) VALUES('delete',old.rowid,old.search_text); END");
            s.execute("CREATE TRIGGER IF NOT EXISTS history_update AFTER UPDATE ON history_entries BEGIN INSERT INTO history_search(history_search,rowid,search_text) VALUES('delete',old.rowid,old.search_text); INSERT INTO history_search(rowid,search_text) VALUES(new.rowid,new.search_text); END");
        }
        HistoryPageQuery.create(c);
    }

    static void upgradeListing(Connection c) throws SQLException {
        HistoryPageQuery.create(c);
        try(var s=c.createStatement();var rows=s.executeQuery("SELECT source,scope,kind,metadata FROM history_entries")) {
            while(rows.next()) HistoryPageQuery.put(c,rows.getString(1),rows.getString(2),rows.getString(3),record(rows.getString(4)));
        }
    }

    static JSONObject metadata(TradeRecord r) {
        return new JSONObject().put("filename",r.filename).put("date",r.date.toString())
                .put("customer",r.customerName).put("trader",r.traderName).put("payment",r.paymentMethod)
                .put("total",r.totalValue.toPlainString()).put("cards",r.totalCards)
                .put("id",r.tradeId==null ? "" : r.tradeId.toString()).put("revision",r.revision).put("inventoried",r.inventoried);
    }
    static TradeRecord record(String text) {
        var j=new JSONObject(text);String id=j.getString("id");
        return new TradeRecord(j.getString("filename"),LocalDateTime.parse(j.getString("date")),j.getString("customer"),
                j.getString("trader"),j.getString("payment"),new BigDecimal(j.getString("total")),j.getInt("cards"),
                id.isEmpty() ? null : UUID.fromString(id),j.getLong("revision"),j.getBoolean("inventoried"));
    }
    static void put(Connection c,String source,String scope,String kind,String stamp,Loaded loaded) throws SQLException {
        var r=loaded.record();
        String search=(r.customerName+"\n"+r.traderName+"\n"+r.date.toString().replace('T',' ')+"\n"+loaded.body()).toLowerCase(Locale.ROOT);
        try (var s=c.prepareStatement("INSERT INTO history_entries VALUES(?,?,?,?,?,?,?) ON CONFLICT(source) DO UPDATE SET scope=excluded.scope,kind=excluded.kind,stamp=excluded.stamp,metadata=excluded.metadata,body=excluded.body,search_text=excluded.search_text")) {
            s.setString(1,source);s.setString(2,scope);s.setString(3,kind);s.setString(4,stamp);
            s.setString(5,metadata(r).toString());s.setString(6,loaded.body());s.setString(7,search);s.executeUpdate();
        }
        HistoryPageQuery.put(c,source,scope,kind,r);
    }
    List<Entry> entries(String scope,String kind) throws SQLException {
        var result=new ArrayList<Entry>();
        var deleted=new TradeDeletionStore(repository).snapshot();
        try (var c=repository.connect();var s=c.prepareStatement("SELECT source,stamp,metadata FROM history_entries WHERE scope=? AND kind=?")) {
            s.setString(1,scope);s.setString(2,kind);
            try (var rows=s.executeQuery()) { while(rows.next()) {
                var record=record(rows.getString(3));
                if(!deleted.contains(record)) result.add(new Entry(rows.getString(1),rows.getString(2),record));
            } }
        }
        return result;
    }
    String body(String source,String token) throws SQLException {
        try (var c=repository.connect();var s=c.prepareStatement("SELECT body,stamp FROM history_entries WHERE source=?")) {
            s.setString(1,source);try (var rows=s.executeQuery()) {
                return rows.next() && token.startsWith(source+"\n"+rows.getString(2)+"\n") ? rows.getString(1) : null;
            }
        }
    }
    Set<String> search(String query) throws SQLException {
        String normalized=query.toLowerCase(Locale.ROOT);
        boolean indexed=normalized.codePointCount(0,normalized.length())>=3;
        String sql=indexed ? "SELECT h.source FROM history_search JOIN history_entries h ON h.rowid=history_search.rowid WHERE history_search MATCH ?"
                : "SELECT source FROM history_entries WHERE instr(search_text,?)>0";
        var result=new HashSet<String>();
        try (var c=repository.connect();var s=c.prepareStatement(sql)) {
            s.setString(1,indexed ? "\""+normalized.replace("\"","\"\"")+"\"" : normalized);
            try (var rows=s.executeQuery()) { while(rows.next()) result.add(rows.getString(1)); }
        }
        return result;
    }
    static String scope(Path directory) { return directory.toAbsolutePath().normalize().toString(); }

    Scan scan(Path directory,String kind,Set<String> ignored,boolean keepOffline,Loader loader) throws Exception {
        var deleted=new TradeDeletionStore(repository).snapshot();
        String scope=scope(directory);
        Map<String,String> old=new HashMap<>();
        try(var c=repository.connect();var s=c.prepareStatement("SELECT source,stamp FROM history_entries WHERE scope=? AND kind=?")) {
            s.setString(1,scope);s.setString(2,kind);
            try(var rows=s.executeQuery()) { while(rows.next()) old.put(rows.getString(1),rows.getString(2)); }
        }
        var changed=new LinkedHashMap<String,Loaded>();var stamps=new HashMap<String,String>();
        var present=new HashSet<String>();
        if (!Files.isDirectory(directory)) {
            if (keepOffline) return new Scan(0,false);
            if (!Files.notExists(directory)) throw new java.io.IOException("History folder unavailable: "+directory);
        } else try(var files=Files.newDirectoryStream(directory)) {
            for(Path file:files) {
                String name=file.getFileName().toString();
                if (!name.endsWith(kind.equals("revision") ? ".json" : ".txt") || ignored.contains(name)) continue;
                if(deleted.containsFile(name) || (kind.equals("revision") && deleted.keys().contains("trade:"+name.substring(0,name.length()-5)))) continue;
                var attributes=Files.readAttributes(file,BasicFileAttributes.class);
                if (!attributes.isRegularFile()) continue;
                String source="file:"+file.toAbsolutePath().normalize();present.add(source);
                String stamp=attributes.lastModifiedTime()+":"+attributes.size();
                if (kind.equals("receipt")) {
                    Path companion=file.resolveSibling(name.substring(0,name.length()-4)+".json");
                    try { var a=Files.readAttributes(companion,BasicFileAttributes.class);stamp+=":"+a.lastModifiedTime()+":"+a.size(); }
                    catch (NoSuchFileException absent) { stamp+=":no-json"; }
                }
                var existing=old.get(source);
                if (existing!=null && existing.equals(stamp)) continue;
                changed.put(source,loader.read(file));stamps.put(source,stamp);
            }
        }
        if(changed.isEmpty() && present.containsAll(old.keySet())) return new Scan(0,true);
        // Publish a complete directory scan atomically; a network failure retains the previous cache.
        try (var c=repository.connect()) {
            c.setAutoCommit(false);
            try {
                for(var e:changed.entrySet()) put(c,e.getKey(),scope,kind,stamps.get(e.getKey()),e.getValue());
                try(var delete=c.prepareStatement("DELETE FROM history_entries WHERE source=?")) {
                    for(String missing:old.keySet()) if(!present.contains(missing)) { delete.setString(1,missing);delete.addBatch(); }
                    delete.executeBatch();
                }
                c.commit();
            } catch(Exception failure) { c.rollback();throw failure; }
        }
        return new Scan(changed.size(),true);
    }
}
