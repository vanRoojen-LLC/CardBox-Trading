package com.cardpricer.service;

import com.cardpricer.model.TradeRecord;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** SQL filtering, de-duplication and paging; only returned rows decode receipt metadata. */
final class HistoryPageQuery {
    static void create(Connection c) throws SQLException {
        try(var s=c.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS history_listing(source TEXT PRIMARY KEY REFERENCES history_entries(source) ON DELETE CASCADE,"
                    +"record_key TEXT NOT NULL,filename TEXT NOT NULL,date TEXT NOT NULL,revision INTEGER NOT NULL,payment TEXT NOT NULL,"
                    +"total_cents INTEGER NOT NULL,cards INTEGER NOT NULL,scope TEXT NOT NULL,kind TEXT NOT NULL)");
            s.execute("CREATE INDEX IF NOT EXISTS history_listing_date ON history_listing(date DESC,record_key,source)");
            s.execute("CREATE INDEX IF NOT EXISTS history_listing_key ON history_listing(record_key,revision DESC)");
        }
    }
    static void put(Connection c,String source,String scope,String kind,TradeRecord r) throws SQLException {
        try(var s=c.prepareStatement("INSERT INTO history_listing VALUES(?,?,?,?,?,?,?,?,?,?) ON CONFLICT(source) DO UPDATE SET "
                +"record_key=excluded.record_key,filename=excluded.filename,date=excluded.date,revision=excluded.revision,payment=excluded.payment,"
                +"total_cents=excluded.total_cents,cards=excluded.cards,scope=excluded.scope,kind=excluded.kind")) {
            s.setString(1,source);s.setString(2,r.historyKey());s.setString(3,Path.of(r.filename).getFileName().toString());
            s.setString(4,r.date.format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS")));
            s.setLong(5,r.revision);s.setString(6,r.paymentMethod.toLowerCase(Locale.ROOT));
            s.setLong(7,r.totalValue.movePointRight(2).longValueExact());s.setInt(8,r.totalCards);s.setString(9,scope);s.setString(10,kind);s.executeUpdate();
        }
    }
    private static String eligible(String alias) {
        return "("+alias+".kind='managed' OR ("+alias+".kind='receipt' AND "+alias+".scope IN (?,?)) OR ("+alias+".kind='revision' AND "+alias+".scope=?))";
    }
    private static String priority(String alias) {
        return "CASE "+alias+".kind WHEN 'revision' THEN 4 WHEN 'managed' THEN 3 ELSE CASE WHEN "+alias+".scope=? THEN 2 ELSE 1 END END";
    }
    static TradeHistoryService.Page load(TradeRepository repository,Path local,Path shared,
            TradeHistoryService.Filter filter,int limit,TradeHistoryService.Cursor after,String notice) throws Exception {
        if(limit<1 || limit>100_000) throw new IllegalArgumentException("Invalid history page size");
        String localScope=HistoryIndex.scope(local), sharedScope=shared==null ? "" : HistoryIndex.scope(shared);
        String revisionScope=shared==null ? "" : HistoryIndex.scope(shared.resolve(SharedTradeService.DIRECTORY));
        var args=new ArrayList<Object>();
        Collections.addAll(args,localScope,sharedScope,revisionScope,localScope,sharedScope,revisionScope,localScope,localScope,localScope,localScope);
        String status="COALESCE((SELECT CASE WHEN i.revision=h.revision THEN i.inventoried ELSE 0 END FROM inventory_changes i "
                +"WHERE i.record_key=h.record_key ORDER BY i.revision DESC,i.sequence DESC,i.inventoried ASC,i.id DESC LIMIT 1),0)";
        String where=" WHERE "+eligible("h")+" AND NOT EXISTS(SELECT 1 FROM history_listing b WHERE b.record_key=h.record_key AND "+eligible("b")
                +" AND (b.revision>h.revision OR (b.revision=h.revision AND ("+priority("b")+">"+priority("h")
                +" OR ("+priority("b")+"="+priority("h")+" AND b.source<h.source)))))"
                +" AND NOT EXISTS(SELECT 1 FROM trade_deletions d WHERE d.record_key=h.record_key)"
                +" AND NOT EXISTS(SELECT 1 FROM deleted_trade_files d WHERE d.name=h.filename)";
        if(!filter.payment().equalsIgnoreCase("All")) { where+=" AND instr(h.payment,?)>0";args.add(filter.payment().toLowerCase(Locale.ROOT)); }
        if(filter.inventory()!=0) { where+=" AND "+status+"=?";args.add(filter.inventory()==2 ? 1 : 0); }
        String query=filter.query().toLowerCase(Locale.ROOT);
        if(!query.isBlank()) {
            if(query.codePointCount(0,query.length())>=3) {
                where+=" AND h.source IN (SELECT e.source FROM history_search JOIN history_entries e ON e.rowid=history_search.rowid WHERE history_search MATCH ?)";
                args.add("\""+query.replace("\"","\"\"")+"\"");
            } else { where+=" AND h.source IN (SELECT source FROM history_entries WHERE instr(search_text,?)>0)";args.add(query); }
        }
        var records=new ArrayList<TradeRecord>();var sources=new HashMap<String,String>();var tokens=new HashMap<String,String>();
        long count,cards,cents;TradeHistoryService.Cursor next=null;
        try(var c=repository.connect()) {
            c.setAutoCommit(false); // Totals and page share one SQLite read snapshot.
            try(var s=c.prepareStatement("SELECT count(*),coalesce(sum(h.cards),0),coalesce(sum(h.total_cents),0) FROM history_listing h"+where)) {
                bind(s,args);try(var rows=s.executeQuery()) { rows.next();count=rows.getLong(1);cards=rows.getLong(2);cents=rows.getLong(3); }
            }
            var pageArgs=new ArrayList<>(args);
            if(after!=null) {
                where+=" AND (h.date<? OR (h.date=? AND h.record_key>?))";
                Collections.addAll(pageArgs,after.date(),after.date(),after.key());
            }
            pageArgs.add(limit);
            try(var s=c.prepareStatement("SELECT h.source,e.stamp,e.metadata,"+status+",h.date,h.record_key FROM history_listing h "
                    +"JOIN history_entries e ON e.source=h.source"+where+" ORDER BY h.date DESC,h.record_key LIMIT ?")) {
                bind(s,pageArgs);try(var rows=s.executeQuery()) { while(rows.next()) {
                    var r=HistoryIndex.record(rows.getString(3));
                    if(!Path.of(r.filename).isAbsolute()) r=new TradeRecord(local.resolve(r.filename).toString(),r.date,r.customerName,r.traderName,
                            r.paymentMethod,r.totalValue,r.totalCards,r.tradeId,r.revision,r.inventoried);
                    sources.put(r.historyKey(),rows.getString(1));
                    tokens.put(r.historyKey(),rows.getString(1)+"\n"+rows.getString(2)+"\n"+HistoryIndex.metadata(r));
                    records.add(r.withInventoried(rows.getBoolean(4)));
                    next=new TradeHistoryService.Cursor(rows.getString(5),rows.getString(6));
                } }
            }
            c.commit();
        }
        return new TradeHistoryService.Page(new TradeHistoryService.Snapshot(records,sources,tokens,notice),count,cards,BigDecimal.valueOf(cents,2),next);
    }
    private static void bind(PreparedStatement s,List<Object> args) throws SQLException { for(int i=0;i<args.size();i++) s.setObject(i+1,args.get(i)); }
}
