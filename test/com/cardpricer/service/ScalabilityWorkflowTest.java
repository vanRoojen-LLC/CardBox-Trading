package com.cardpricer.service;

import com.cardpricer.gui.panel.trade.TradeTableModel;
import com.cardpricer.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ScalabilityWorkflowTest {
    @TempDir Path temp;

    @Test void editingOneOfTenThousandRowsRepricesOnlyThatRowAndCapturesDetachedState() {
        var table=new TradeTableModel();
        for(int i=0;i<10_000;i++) {
            var card=new Card("Card "+i,"TST",Integer.toString(i));card.setRarity("rare");card.setPrice("10");
            var item=new TradeItem(card,false,1);item.setUnitPrice(new BigDecimal("10.00"));
            table.addTrade(item,"NM",new BigDecimal("10.00"));
        }
        var calls=new AtomicInteger();
        java.util.function.BiFunction<TradeItem,BigDecimal,BuyRateService.PayoutResult> rates=(item,value)-> {
            calls.incrementAndGet();return new BuyRateService.PayoutResult(value.multiply(new BigDecimal("0.50")),
                    value.multiply(new BigDecimal("0.30")),new BigDecimal("0.50"),new BigDecimal("0.30"),false);
        };
        assertEquals(new BigDecimal("100000.00"),table.summary(rates).market());
        var captured=table.captureLines(Map.of());
        calls.set(0);table.setValueAt(3,5123,4);
        var summary=table.summary(rates);
        assertEquals(1,calls.get());assertEquals(10_002,summary.quantity());
        assertEquals(new BigDecimal("50010.00"),summary.credit());
        var next=table.captureLines(Map.of());
        assertSame(captured.get(5122),next.get(5122));assertNotSame(captured.get(5123),next.get(5123));
        assertEquals(1,captured.get(5123).encode().quantity());assertEquals(3,next.get(5123).encode().quantity());
        table.removeRow(5123);summary=table.summary(rates);
        assertEquals(new BigDecimal("99990.00"),summary.market());assertEquals(9999,summary.quantity());
        assertEquals(1,calls.get(),"Removal subtracts its cached contribution");
        table.invalidatePayouts();table.summary(rates);assertEquals(10_000,calls.get());
    }

    @Test void saveQueueCoalescesPerDraftBoundsWorkAndRetainsFailuresUntilSuccessfulRetry() {
        var workers=new ArrayList<Runnable>();var queue=new DraftSaveQueue(workers::add,2);
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();var written=new ArrayList<Integer>();
        for(int i=0;i<1000;i++) { int revision=i;queue.submit(first,()->written.add(revision),ignored->{}); }
        queue.submit(second,()->written.add(1000),ignored->{});
        assertThrows(IllegalStateException.class,()->queue.submit(UUID.randomUUID(),()->{},ignored->{}));
        assertEquals(1,workers.size());workers.removeFirst().run();queue.flush(Duration.ofSeconds(1));
        assertEquals(List.of(999,1000),written);
        queue.submit(first,()-> { throw new IllegalStateException("disk full"); },failure->assertNotNull(failure));
        workers.removeFirst().run();assertThrows(IllegalStateException.class,()->queue.flush(Duration.ofSeconds(1)));
        queue.submit(first,()->written.add(1001),failure->assertNull(failure));workers.removeFirst().run();queue.flush(Duration.ofSeconds(1));
    }

    @Test void independentDraftsAndEditParentSurviveRestartAndOnlyApprovedDraftIsRemoved() throws Exception {
        Path database=temp.resolve("ledger.sqlite");var repo=new TradeRepository(database);
        var first=TradeRepositoryTest.draft("TST");var second=TradeRepositoryTest.draft("PLST");
        repo.saveRecoveryDraft(first,null);repo.saveRecoveryDraft(second,null);
        repo=new TradeRepository(database);assertEquals(2,repo.recoveryDrafts().size());
        repo.commit(first,TradeApplicationService.outputs(first,false));
        assertEquals(List.of(second.id()),repo.recoveryDrafts().stream().map(TradeRepository.DraftSummary::id).toList());
        var edit=new TradeDraft(first.id(),2,first.trader(),"Edited customer",first.identification(),first.checkNumber(),first.payment(),
                first.credit(),first.check(),first.lines(),first.quotedAt(),first.rateRevision());
        repo.saveRecoveryDraft(edit,first.revision());repo=new TradeRepository(database);
        assertEquals(edit,repo.recoveryDraft(edit.id()).draft());assertEquals(first.revision(),repo.recoveryDraft(edit.id()).editingRevision());
        repo.revise(edit,first.revision(),TradeApplicationService.outputs(edit,true));
        repo.saveRecoveryDraft(edit,first.revision()); // Late save cannot resurrect an accepted correction.
        assertEquals(1,repo.recoveryDrafts().size());assertEquals(second,repo.recoveryDraft(second.id()).draft());
        repo.deleteDraft(second.id());assertTrue(repo.recoveryDrafts().isEmpty());
    }

    @Test void competingApprovedRevisionCannotDeleteOrSuppressDifferentLocalEdits() throws Exception {
        var repo=new TradeRepository(temp.resolve("ledger.sqlite"));var original=TradeRepositoryTest.draft("TST");
        repo.commit(original,TradeApplicationService.outputs(original,false));
        var local=new TradeDraft(original.id(),2,original.trader(),"Local edits",original.identification(),original.checkNumber(),original.payment(),
                original.credit(),original.check(),original.lines(),original.quotedAt(),original.rateRevision());
        var remote=new TradeDraft(original.id(),2,original.trader(),"Another workstation",original.identification(),original.checkNumber(),original.payment(),
                original.credit(),original.check(),original.lines(),original.quotedAt(),original.rateRevision());
        repo.saveRecoveryDraft(local,1L);repo.revise(remote,1,TradeApplicationService.outputs(remote,true));
        assertEquals(local,repo.recoveryDraft(original.id()).draft());
        repo.saveRecoveryDraft(local,1L);assertEquals(local,repo.recoveryDraft(original.id()).draft());
        assertThrows(java.sql.SQLException.class,()->repo.revise(local,1,TradeApplicationService.outputs(local,true)));
        assertEquals(remote,repo.committedDraft(original.id()));
    }

    @Test void versionFiveLedgerMigratesDraftAndHistoryWithoutRepricing() throws Exception {
        Path local=temp.resolve("trades"),database=temp.resolve("ledger/trades.sqlite");
        var repo=new TradeRepository(database);var draft=TradeRepositoryTest.draft("TST");repo.saveDraft(draft);
        var approved=TradeRepositoryTest.draft("TST");repo.commit(approved,TradeApplicationService.outputs(approved,false));
        try(var c=repo.connect();var s=c.createStatement()) {
            s.execute("DROP TABLE history_listing");s.execute("ALTER TABLE drafts DROP COLUMN editing_revision");
            s.execute("ALTER TABLE drafts DROP COLUMN updated_at");s.execute("PRAGMA user_version=5");
        }
        repo=new TradeRepository(database);
        assertEquals(draft,repo.recoveryDraft(draft.id()).draft());assertEquals(approved,repo.committedDraft(approved.id()));
        var page=new TradeHistoryService.Session(local.toString(),"").page(new TradeHistoryService.Filter("","All",0),100,null,"");
        assertEquals(1,page.count());assertEquals(approved.settle().market(),page.total());
    }

    @Test void legacyTypedDraftMigratesWithItsEditParentAndAnOriginalBackup() throws Exception {
        var draft=TradeRepositoryTest.draft("TST");
        Path session=com.cardpricer.util.AppDataDirectory.root().toPath().resolve("session");Files.createDirectories(session);
        Path old=session.resolve("draft-v1.json");
        assertFalse(Files.exists(old));Files.writeString(old,draft.toJson().put("editingRevision",0).toString());
        try {
            var restored=TradeSessionService.find(draft.id());
            assertEquals(draft,restored.draft());assertEquals(0L,restored.editingRevision());
            assertFalse(Files.exists(old));
            assertTrue(Files.exists(session.resolve("migrated-draft-"+draft.id()+"-1.json")));
        } finally { TradeSessionService.discard(draft.id()); }
    }

    @Test void pagesFilterBeforeLimitingCountExactlyAndDoNotDecodeUnreturnedMetadata() throws Exception {
        Path local=temp.resolve("trades");var repo=TradeHistoryService.repository(local.toString());
        try(var c=repo.connect()) {
            c.setAutoCommit(false);
            for(int i=0;i<250;i++) {
                var r=new TradeRecord(local.resolve(i+".txt").toString(),LocalDateTime.of(2026,1,1,0,0).plusDays(i),"Customer "+i,"Staff",
                        i%2==0 ? "Check" : "Store credit",new BigDecimal("10.01"),3);
                HistoryIndex.put(c,"file:"+r.filename,HistoryIndex.scope(local),"receipt","1",new HistoryIndex.Loaded(r,i==0 ? "Black Lotus 日本 a\"b" : "Ordinary"));
            }
            c.commit();
        }
        var session=new TradeHistoryService.Session(local.toString(),"");var filter=new TradeHistoryService.Filter("","All",0);
        var first=session.page(filter,100,null,"");assertEquals(100,first.snapshot().records().size());assertEquals(250,first.count());
        assertEquals(new BigDecimal("2502.50"),first.total());assertEquals(750,first.cards());
        var second=session.page(filter,100,first.next(),"");var third=session.page(filter,100,second.next(),"");
        var keys=new HashSet<String>();
        for(var page:List.of(first,second,third)) for(var r:page.snapshot().records()) assertTrue(keys.add(r.historyKey()));
        assertEquals(250,keys.size());
        assertEquals(125,session.page(new TradeHistoryService.Filter("","Check",0),100,null,"").count());
        for(String query:List.of("lotus","日本","a\"b")) {
            var found=session.page(new TradeHistoryService.Filter(query,"All",0),100,null,"");
            assertEquals(1,found.count());assertEquals("Customer 0",found.snapshot().records().getFirst().customerName);
        }
        var selected=first.snapshot().records().getFirst();repo.setInventoried(selected,true);
        assertEquals(1,session.page(new TradeHistoryService.Filter("","All",2),100,null,"").count());
        assertEquals(249,session.page(new TradeHistoryService.Filter("","All",1),100,null,"").count());
        // A malformed off-page metadata blob cannot affect a first-page query or its aggregates.
        try(var c=repo.connect();var s=c.prepareStatement("UPDATE history_entries SET metadata='invalid' WHERE source=?")) {
            s.setString(1,"file:"+local.resolve("0.txt"));s.executeUpdate();
        }
        assertEquals(100,session.page(filter,100,null,"").snapshot().records().size());
    }
}
