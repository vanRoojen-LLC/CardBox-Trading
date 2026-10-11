package com.cardpricer.service;

import com.cardpricer.model.TradeDraft;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class HistoryIndexTest {
    @TempDir Path temp;

    private static String receipt(String customer,String body) {
        return "Customer Name: "+customer+"\nTrader Name: Staff\nPayment Method: Check\nTotal Value: $24.00\nTotal Cards: 3\n"+body;
    }
    private static TradeDraft corrected(TradeDraft original,String customer) {
        return new TradeDraft(original.id(),original.revision()+1,original.trader(),customer,original.identification(),
                original.checkNumber(),original.payment(),original.credit(),original.check(),original.lines(),original.quotedAt(),original.rateRevision());
    }

    @Test void repeatScanAndRestartReuseIndexButChangedAndDeletedFilesAreUpdated() throws Exception {
        Path directory=Files.createDirectories(temp.resolve("trades"));
        Path file=directory.resolve("2026-01-01_10-00-00_old.txt");
        Files.writeString(file,receipt("Before","Black Lotus"));
        var session=new TradeHistoryService.Session(directory.toString(),"");
        var first=session.refresh();
        assertEquals(1,session.lastReadCount());
        assertEquals(Set.of(first.records().getFirst().historyKey()),session.search(first,"Lotus"));
        session.refresh();assertEquals(0,session.lastReadCount());

        var restarted=new TradeHistoryService.Session(directory.toString(),"");
        var cached=restarted.cached();
        assertEquals(first.tokens(),cached.tokens());
        assertEquals("Before",cached.records().getFirst().customerName);
        assertTrue(restarted.receipt(cached,cached.records().getFirst()).contains("Black Lotus"));
        restarted.refresh();assertEquals(0,restarted.lastReadCount());

        Files.writeString(file,receipt("After correction","Mox Sapphire"));
        var changed=restarted.refresh();assertEquals(1,restarted.lastReadCount());
        assertEquals("After correction",changed.records().getFirst().customerName);
        assertTrue(restarted.search(changed,"Lotus").isEmpty());
        assertEquals(1,restarted.search(changed,"Sapphire").size());
        assertThrows(IOException.class,()->restarted.receipt(cached,cached.records().getFirst()),"An old row must not preview the replacement receipt");
        Files.delete(file);
        assertTrue(restarted.refresh().records().isEmpty());
        assertTrue(restarted.search(changed,"Sapphire").isEmpty());
    }

    @Test void offlineSharedHistoryRemainsSearchableAfterRestart() throws Exception {
        Path local=temp.resolve("station/trades"),shared=Files.createDirectory(temp.resolve("shared"));
        Path file=shared.resolve("2026-01-01_10-00-00_shared.txt");
        Files.writeString(file,receipt("Shared customer","Tropical Island"));
        var session=new TradeHistoryService.Session(local.toString(),shared.toString());
        var first=session.refresh();assertEquals(1,first.records().size());
        Files.move(shared,temp.resolve("offline"));
        var restarted=new TradeHistoryService.Session(local.toString(),shared.toString());
        assertEquals(first.tokens(),restarted.cached().tokens());
        var offline=restarted.refresh();assertFalse(offline.notice().isEmpty());
        assertEquals(1,restarted.page(new TradeHistoryService.Filter("Tropical","All",0),100,null,offline.notice()).count());
        assertEquals(1,restarted.search(offline,"Tropical").size());
        assertTrue(restarted.receipt(offline,offline.records().getFirst()).contains("Tropical Island"));
        Files.move(temp.resolve("offline"),shared);
        assertTrue(restarted.refresh().notice().isEmpty());assertEquals(0,restarted.lastReadCount());
    }

    @Test void aFailedDirectoryScanDoesNotPublishPartialUpdatesOrRemoveCachedFiles() throws Exception {
        Path local=Files.createDirectory(temp.resolve("trades"));
        Path file=local.resolve("2026-01-01_00-00-00.txt");
        Files.writeString(file,receipt("Saved","Original body"));
        var repo=TradeHistoryService.repository(local.toString());var index=new HistoryIndex(repo);
        AtomicInteger reads=new AtomicInteger();
        HistoryIndex.Loader loader=path->{ reads.incrementAndGet();return TradeHistoryService.readReceipt(path); };
        index.scan(local,"receipt",Set.of(),false,loader);
        index.scan(local,"receipt",Set.of(),false,loader);assertEquals(1,reads.get());
        Files.writeString(file,receipt("Changed customer","Changed body"));
        Files.writeString(local.resolve("broken.txt"),"bad");
        assertThrows(IOException.class,()->index.scan(local,"receipt",Set.of(),false,path->{
            if(path.getFileName().toString().equals("broken.txt")) throw new IOException("connection lost");
            return TradeHistoryService.readReceipt(path);
        }));
        var entries=index.entries(HistoryIndex.scope(local),"receipt");
        assertEquals(1,entries.size());assertEquals("Saved",entries.getFirst().record().customerName);
    }

    @Test void managedListUsesSummariesAndCommitFailureCannotPublishAnotherSummary() throws Exception {
        Path local=temp.resolve("trades");var repo=TradeHistoryService.repository(local.toString());
        var original=TradeRepositoryTest.draft("TST");
        new TradeApplicationService(repo,local).finalizeTrade(original);
        var session=new TradeHistoryService.Session(local.toString(),"");
        var initial=session.refresh();assertEquals(0,session.lastReadCount());
        var broken=new TradeRepository(temp.resolve("ledger/trades.sqlite"),point->{
            if(point.equals("beforeCommit")) throw new IllegalStateException("interrupted");
        });
        var correction=corrected(original,"Unsaved customer");
        assertThrows(IllegalStateException.class,()->broken.revise(correction,1,TradeApplicationService.outputs(correction,true)));
        assertEquals(initial.tokens(),session.cached().tokens());
        assertTrue(session.search(session.cached(),"Unsaved customer").isEmpty());

        // A listing must not deserialize or settle the original card snapshot.
        try(var c=repo.connect();var s=c.createStatement()) { s.executeUpdate("UPDATE trades SET snapshot='not JSON'"); }
        var summary=new TradeHistoryService.Session(local.toString(),"").cached();
        assertEquals(initial.tokens(),summary.tokens());
        assertEquals(original.settle().market(),summary.records().getFirst().totalValue);
        assertTrue(session.receipt(summary,summary.records().getFirst()).contains(original.customer()));
    }

    @Test void sharedCorrectionsReplaceSearchAndPreviewWithoutRereadingUnchangedDocuments() throws Exception {
        Path local=temp.resolve("a/trades"),shared=Files.createDirectory(temp.resolve("shared"));
        var repo=TradeHistoryService.repository(local.toString());
        var original=corrected(TradeRepositoryTest.draft("TST"),"OriginalUniqueCustomer");
        new TradeApplicationService(repo,local).finalizeTrade(original);
        try(var files=Files.list(local)) { for(Path file:files.toList()) Files.copy(file,shared.resolve(file.getFileName())); }
        var sharedService=new SharedTradeService(repo,local,shared);sharedService.open(original.id(),original.revision());
        var session=new TradeHistoryService.Session(temp.resolve("b/trades").toString(),shared.toString());
        var first=session.refresh();
        assertEquals(1,first.records().size());assertEquals(1,session.search(first,"OriginalUniqueCustomer").size());
        session.refresh();assertEquals(0,session.lastReadCount());
        var correction=corrected(original,"NewUniqueCustomer");sharedService.update(correction,original.revision());
        var updated=session.refresh();
        assertEquals(0,session.page(new TradeHistoryService.Filter("OriginalUniqueCustomer","All",0),100,null,"").count());
        var page=session.page(new TradeHistoryService.Filter("NewUniqueCustomer","All",0),100,null,"");
        assertEquals(1,page.count());assertEquals(correction.revision(),page.snapshot().records().getFirst().revision);
        assertEquals(1,updated.records().size());assertEquals(correction.revision(),updated.records().getFirst().revision);
        assertTrue(session.search(updated,"OriginalUniqueCustomer").isEmpty(),"Superseded receipts must not match");
        assertEquals(1,session.search(updated,"NewUniqueCustomer").size());
        assertThrows(IOException.class,()->session.receipt(first,first.records().getFirst()));
        assertTrue(session.receipt(updated,updated.records().getFirst()).contains("NewUniqueCustomer"));
        session.refresh();assertEquals(0,session.lastReadCount());
    }

    @Test void searchIncludesOlderReceiptsAndTreatsOperatorsAndShortUnicodeTextLiterally() throws Exception {
        Path local=Files.createDirectory(temp.resolve("trades"));
        for(int i=0;i<125;i++) {
            String date=java.time.LocalDateTime.of(2026,1,1,0,0).plusDays(i).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Files.writeString(local.resolve(date+".txt"),receipt("Customer "+i,i==0 ? "Black Lotus | 日本語 | A OR B | 50% off | a\"b | x*y" : "Ordinary"));
        }
        var session=new TradeHistoryService.Session(local.toString(),"");var snapshot=session.refresh();
        var oldest=snapshot.records().getLast();assertEquals("Customer 0",oldest.customerName);
        for(String query:List.of("lotus","日本","日本語","A OR B","50%","a\"b","x*y","2026-01-01 00:00"))
            assertEquals(Set.of(oldest.historyKey()),session.search(snapshot,query),query);
        assertTrue(session.search(snapshot,"Lotus OR Ordinary").isEmpty());
        session.refresh();assertEquals(0,session.lastReadCount());
    }
}
