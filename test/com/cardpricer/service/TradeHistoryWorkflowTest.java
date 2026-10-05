package com.cardpricer.service;

import com.cardpricer.model.TradeDraft;
import com.cardpricer.model.TradeRecord;
import com.cardpricer.model.Card;
import com.cardpricer.model.Condition;
import com.cardpricer.model.Finish;
import com.cardpricer.model.TradeLine;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.math.BigDecimal;
import java.sql.*;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TradeHistoryWorkflowTest {
    @TempDir Path temp;

    private static TradeDraft corrected(TradeDraft original, String customer) {
        return new TradeDraft(original.id(), original.revision()+1, original.trader(), customer,
                original.identification(), original.checkNumber(), original.payment(), original.credit(),
                original.check(), original.lines(), original.quotedAt(), original.rateRevision());
    }

    @Test void filenamesKeepReadableNamesAndDateWhileRemovingUnsafePathCharacters() {
        var date=java.time.LocalDateTime.of(2026,9,15,14,30,0);
        var id=java.util.UUID.fromString("a1b2c3d4-e5f6-4789-8123-123456789012");
        assertEquals("2026-09-15_14-30-00 - Alex Morgan - Sam Lee - a1b2c3d4e5f6",
                TradeApplicationService.filenamePrefix(date,"Alex Morgan","Sam Lee",id));
        String safe=TradeApplicationService.filenamePrefix(date,"../Éowyn\\ Smith:*?\"<>|", "Sam\nLee",id);
        assertEquals("2026-09-15_14-30-00 - Éowyn Smith - Sam Lee - a1b2c3d4e5f6",safe);
        assertEquals(1,Path.of(safe+".txt").getNameCount());
        assertTrue(TradeApplicationService.filenamePrefix(date,"...",null,id).contains("Unknown customer - Unknown employee"));
        assertTrue(TradeApplicationService.filenamePrefix(date,"a".repeat(300),"b".repeat(300),id).length()<150);
        assertNotEquals(safe,TradeApplicationService.filenamePrefix(date,"../Éowyn\\ Smith:*?\"<>|","Sam\nLee",java.util.UUID.randomUUID()));
    }

    @Test void correctionKeepsOriginalAndOneHistoryEntryAndResetsPosStatus() throws Exception {
        Path output=temp.resolve("trades");
        var repository=TradeHistoryService.repository(output.toString());
        var service=new TradeApplicationService(repository,output);
        var original=TradeRepositoryTest.draft("TST");
        service.finalizeTrade(original);
        TradeRecord record=TradeHistoryService.loadAll(output.toString()).getFirst();
        String originalReceipt=Files.readString(Path.of(record.filename));
        repository.setInventoried(record,true);
        assertTrue(TradeHistoryService.loadAll(output.toString()).getFirst().inventoried);

        var correction=corrected(original,"Corrected customer");
        assertEquals(0,service.updateTrade(correction,original.revision()));
        assertEquals(originalReceipt,Files.readString(Path.of(record.filename)));
        var history=TradeHistoryService.loadAll(output.toString());
        assertEquals(1,history.size());
        var latest=history.getFirst();
        assertEquals(original.id(),latest.tradeId);
        assertEquals(record.date,latest.date);
        assertEquals("Corrected customer",latest.customerName);
        assertEquals(correction.revision(),latest.revision);
        assertFalse(latest.inventoried);
        assertTrue(latest.filename.endsWith("_rev2.txt"));
        assertTrue(TradeHistoryService.csvPath(latest).getFileName().toString().endsWith("_rev2.csv"));
        assertTrue(Files.isRegularFile(TradeHistoryService.csvPath(latest)));
        assertNotEquals(TradeHistoryService.csvPath(record),TradeHistoryService.csvPath(latest));
        assertTrue(Files.readString(Path.of(latest.filename)).contains("Corrected customer"));
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+temp.resolve("ledger/trades.sqlite"));
             var s=c.createStatement();var rs=s.executeQuery("SELECT snapshot FROM trade_versions")) {
            assertTrue(rs.next());
            assertEquals(original,TradeDraft.fromJson(new JSONObject(rs.getString(1))));
            assertFalse(rs.next());
        }
        repository.setInventoried(latest,true);
        assertTrue(TradeHistoryService.loadAll(output.toString()).getFirst().inventoried);
        repository.setInventoried(latest,false);
        assertFalse(TradeHistoryService.loadAll(output.toString()).getFirst().inventoried);
    }

    @Test void retriedCorrectionIsIdempotentAndStaleEditIsRejected() throws Exception {
        Path output=temp.resolve("trades");
        var repository=TradeHistoryService.repository(output.toString());
        var service=new TradeApplicationService(repository,output);
        var original=TradeRepositoryTest.draft("TST");service.finalizeTrade(original);
        var correction=corrected(original,"New name");
        service.updateTrade(correction,original.revision());
        service.updateTrade(correction,original.revision());
        try (var files=Files.list(output)) { assertEquals(6,files.count()); }
        assertThrows(SQLException.class,()->service.updateTrade(corrected(original,"Stale"),original.revision()));
        assertEquals(correction,repository.committedDraft(original.id()));
    }

    @Test void inventoryTradeCorrectionCanAddListPrintingAlongsideOriginal() throws Exception {
        Path output=temp.resolve("trades");
        var repository=TradeHistoryService.repository(output.toString());
        var service=new TradeApplicationService(repository,output);
        Card card=new Card("Example card","C17","149");card.setProviderId("original");card.setPrice("1.50");
        var originalLine=new TradeLine(UUID.randomUUID(),card.identity(),ProviderCardMapper.toJson(card).toString(),
                Finish.NORMAL,Condition.NM,2,new BigDecimal("1.50"),new BigDecimal("1.50"),new BigDecimal("0.50"),new BigDecimal("0.40"));
        var original=new TradeDraft(UUID.randomUUID(),1,"Operator","Customer","","","inventory",
                BigDecimal.ZERO,BigDecimal.ZERO,List.of(originalLine));
        assertEquals(0,service.finalizeTrade(original));
        String originalCsv=Files.readString(output.resolve(TradeApplicationService.outputPrefix(original,false)+".csv"));

        card.setSetCode("PLST");card.setCollectorNumber("C17-149");card.setProviderId("listed");card.setPrice("2.50");
        var listLine=new TradeLine(UUID.randomUUID(),card.identity(),ProviderCardMapper.toJson(card).toString(),
                Finish.NORMAL,Condition.NM,3,new BigDecimal("2.50"),new BigDecimal("2.50"),new BigDecimal("0.50"),new BigDecimal("0.40"));
        var correction=new TradeDraft(original.id(),2,original.trader(),original.customer(),"","","inventory",
                BigDecimal.ZERO,BigDecimal.ZERO,List.of(originalLine,listLine));
        assertEquals(0,service.updateTrade(correction,original.revision()));
        assertEquals(correction,repository.committedDraft(original.id()));
        assertTrue(repository.pending().isEmpty());
        assertEquals(originalCsv,Files.readString(output.resolve(TradeApplicationService.outputPrefix(original,false)+".csv")));
        var history=TradeHistoryService.loadAll(output.toString());
        assertEquals(1,history.size());assertEquals(2,history.getFirst().revision);
        try (var parser=org.apache.commons.csv.CSVFormat.RFC4180.parse(Files.newBufferedReader(TradeHistoryService.csvPath(history.getFirst())))) {
            var rows=parser.getRecords();assertEquals(3,rows.size());
            assertEquals(List.of("C17 149","C17 149"),rows.subList(1,3).stream().map(row -> row.get(4)).toList());
            assertEquals(List.of("2","3"),rows.subList(1,3).stream().map(row -> row.get(9)).toList());
            assertEquals(List.of("0.00","0.00"),rows.subList(1,3).stream().map(row -> row.get(16)).toList());
            assertEquals(List.of("1.50","2.50"),rows.subList(1,3).stream().map(row -> row.get(18)).toList());
        }
        var saved=TradeDraft.fromJson(new JSONObject(Files.readString(output.resolve(TradeApplicationService.outputPrefix(correction,true)+".json"))));
        assertEquals(correction,saved);
    }

    @Test void correctionCommitAndJobsRollBackTogether() throws Exception {
        var repository=new TradeRepository(temp.resolve("ledger.sqlite"));
        var original=TradeRepositoryTest.draft("TST");
        repository.commit(original,List.of());
        var duplicate=new TradeRepository.Output("same.txt","same");
        assertThrows(SQLException.class,()->repository.revise(corrected(original,"New"),original.revision(),List.of(duplicate,duplicate)));
        assertEquals(original,repository.committedDraft(original.id()));
        assertTrue(repository.pending().isEmpty());
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+temp.resolve("ledger.sqlite"));var s=c.createStatement();
             var rs=s.executeQuery("SELECT count(*) FROM trade_versions")) {
            assertEquals(0,rs.getInt(1));
        }
    }

    @Test void pendingCorrectionCanBePreviewedAndRecoveredWithoutDuplicateHistory() throws Exception {
        Path output=temp.resolve("trades");
        var repository=TradeHistoryService.repository(output.toString());
        var original=TradeRepositoryTest.draft("TST");
        new TradeApplicationService(repository,output).finalizeTrade(original);
        Path blocked=temp.resolve("blocked");Files.writeString(blocked,"not a directory");
        var correction=corrected(original,"Updated with exports pending");
        assertEquals(3,new TradeApplicationService(repository,blocked).updateTrade(correction,original.revision()));
        var history=TradeHistoryService.loadAll(output.toString());
        assertEquals(1,history.size());
        var latest=history.getFirst();
        assertFalse(Files.exists(Path.of(latest.filename)));
        assertTrue(TradeHistoryService.receiptContent(latest,output.toString()).contains(correction.customer()));
        var restarted=TradeHistoryService.repository(output.toString());
        assertEquals(0,restarted.retryOutputs(output));
        assertEquals(1,TradeHistoryService.loadAll(output.toString()).size());
        assertTrue(Files.exists(Path.of(latest.filename)));
    }

    @Test void olderDatabaseMigratesWithoutLosingTrades() throws Exception {
        Path database=temp.resolve("old.sqlite");
        var original=TradeRepositoryTest.draft("TST");
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+database);var s=c.createStatement()) {
            s.execute("CREATE TABLE trades(id TEXT PRIMARY KEY, approved_at TEXT NOT NULL, snapshot TEXT NOT NULL)");
            s.execute("PRAGMA user_version=1");
            try (var insert=c.prepareStatement("INSERT INTO trades VALUES(?,?,?)")) {
                insert.setString(1,original.id().toString());insert.setString(2,"2026-09-15T12:00:00Z");
                insert.setString(3,original.toJson().toString());insert.executeUpdate();
            }
        }
        var repository=new TradeRepository(database);
        assertEquals(original,repository.committedDraft(original.id()));
        var record=repository.history(temp).getFirst();repository.setInventoried(record,true);
        assertTrue(repository.inventoryStatuses(List.of(record)).getFirst().inventoried);
        repository.revise(corrected(original,"Migrated"),original.revision(),List.of());
        assertEquals("Migrated",repository.committedDraft(original.id()).customer());
    }

    @Test void legacyReceiptEditsKeepBackupAndResetStatus() throws Exception {
        Path output=temp.resolve("trades");Files.createDirectories(output);
        Path file=output.resolve("2025-01-02_10-00-00_customer.txt");
        String original="Customer Name: Before\nTrader Name: Staff\nPayment Method: Check\nTotal Value: $24.00\nTotal Cards: 3\n";
        Files.writeString(file,original);
        var record=TradeHistoryService.loadAll(output.toString()).getFirst();
        var repository=TradeHistoryService.repository(output.toString());repository.setInventoried(record,true);
        TradeHistoryService.saveLegacyReceipt(record,original.replace("Before","After"),output.toString());
        var updated=TradeHistoryService.loadAll(output.toString()).getFirst();
        assertEquals("After",updated.customerName);assertFalse(updated.inventoried);
        assertEquals(record.date,updated.date);
        try (var c=DriverManager.getConnection("jdbc:sqlite:"+temp.resolve("ledger/trades.sqlite"));var s=c.createStatement();
             var rs=s.executeQuery("SELECT attachment FROM legacy_records ORDER BY imported_at LIMIT 1")) {
            assertTrue(rs.next());assertEquals(original,new String(rs.getBytes(1),java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test void receiptSeparatesCardDetailsFromExactPayoutTotals() {
        var draft=TradeRepositoryTest.draft("TST");
        String receipt=TradeApplicationService.receipt(draft,draft.settle());
        assertTrue(receipt.contains("CUSTOMER & PAYMENT\n"));
        assertTrue(receipt.contains("1. " + draft.lines().getFirst().card().getName()));
        assertTrue(receipt.contains("Quantity: 3 | Market each: $8.00 | Market total: $24.00"));
        assertTrue(receipt.contains("Payout: $9.60 (Credit $0.00 + Check $9.60)"));
        assertTrue(receipt.contains("TRADE TOTALS\n"));
        assertTrue(receipt.contains("TOTAL PAYOUT: $9.60"));
    }
}
