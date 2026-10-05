package com.cardpricer.service;

import com.cardpricer.model.Card;
import com.cardpricer.model.CardEntry;
import com.cardpricer.model.OrderItem;
import com.cardpricer.model.TradeItem;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

class CsvExportTest {
    @TempDir Path directory;

    private static List<CSVRecord> parse(String csv) throws IOException {
        try (var parser = CSVFormat.RFC4180.parse(new java.io.StringReader(csv))) {
            return parser.getRecords();
        }
    }

    private static Card card(String name, String artist) {
        Card card = new Card(name, "TST", "12");
        card.setArtist(artist);
        card.setRarity("rare");
        card.setPrice("12.34");
        card.setFoilPrice("20.00");
        return card;
    }

    @ParameterizedTest
    @ValueSource(strings = {"Ordinary name", "Name, with comma", "Only \"quotes\"", "Line one\r\nLine two", "Éowyn 日本語"})
    void catalogFormatsPreserveText(String text) throws Exception {
        CardEntry entry = new CardEntry(text, text, new BigDecimal("12.34"), "rare", text);
        for (var format : CsvExportService.ExportFormat.values()) {
            var records = parse(CardCsvEncoder.row(entry, format));
            assertEquals(1, records.size());
            CSVRecord row = records.getFirst();
            int offset = format == CsvExportService.ExportFormat.IMPORT_UTILITY ? 2 : 0;
            assertEquals(text, row.get(offset));
            assertEquals(text, row.get(offset + 1));
            int expectedSize = switch (format) {
                case IMPORT_UTILITY -> 8;
                case ITEM_WIZARD -> 9;
                case ITEM_WIZARD_CHANGE_QTY_ZERO -> 5;
            };
            assertEquals(expectedSize, row.size());
            if (format != CsvExportService.ExportFormat.ITEM_WIZARD) assertEquals(text, row.get(offset + 2));
        }
    }

    @ParameterizedTest
    @EnumSource(CsvExportService.ExportFormat.class)
    void individualAndCombinedExportsHaveIdenticalBytes(CsvExportService.ExportFormat format) throws Exception {
        List<Card> cards = List.of(card("Name, \"quoted\"\n日本語", "Artist \"name\""));
        new CsvExportService(directory).exportCardsToCsv(cards, "one-set.csv", format);
        StringWriter combined = new StringWriter();
        CardCsvEncoder.write(combined, CsvExportService.flattenCards(cards), format);
        assertEquals(combined.toString(), Files.readString(directory.resolve("prices/one-set.csv")));
        var rows = parse(combined.toString());
        int firstData = format == CsvExportService.ExportFormat.IMPORT_UTILITY ? 1 : 0;
        assertEquals(2 + firstData, rows.size());
        if (format == CsvExportService.ExportFormat.ITEM_WIZARD_CHANGE_QTY_ZERO) {
            assertEquals(List.of("TST 12", cards.getFirst().getName(), cards.getFirst().getArtist(), "", "0"),
                    rows.getFirst().toList());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"en-US", "de-DE", "fr-FR", "tr-TR"})
    void tradeColumnsAndNumbersAreStableAcrossLocales(String locale) throws Exception {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag(locale));
            List<String> published = new ArrayList<>();
            var service = new TradeReceivingExportService(directory, published::add);
            Card card = card("Éowyn, \"Shieldmaiden\"\r\nSecond line", "Artist");
            String filename = service.exportToPOSFormat(List.of(new TradeItem(card, false)), "Trader", "Customer",
                    List.of(new BigDecimal("100.25")), List.of(3), "credit");
            var rows = parse(Files.readString(Path.of(filename)));
            assertEquals(2, rows.size());
            assertEquals(19, rows.getFirst().size());
            assertEquals(List.of("1", "5", "5.2", "", "TST 12", "", "", "Éowynɕ \"Shieldmaiden\"\r\nSecond line", "", "3", "", "", "",
                    "50.13", "", "", "150.39", "TAX", "100.25"), rows.get(1).toList());
            assertEquals("COST", rows.getFirst().get(13));
            assertEquals("EXTENDED COST", rows.getFirst().get(16));
            assertEquals(List.of(filename), published);

            StringWriter invoice = new StringWriter();
            CsvExportService.writeInvoice(invoice, List.of(new OrderItem(card, false, 2)));
            var invoiceRows = parse(invoice.toString());
            assertTrue(invoiceRows.stream().allMatch(row -> row.size() == 6));
            assertEquals("12.34", invoiceRows.get(1).get(4));
            assertEquals(List.of("TOTAL", "", "", "2", "", "24.68"), invoiceRows.get(2).toList());

            CardEntry entry = new CardEntry(card, false);
            assertEquals("12.00", parse(entry.toImportUtilityRow()).getFirst().get(7));
            assertEquals("12.00", parse(entry.toItemWizardRow()).getFirst().get(8));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void inventoryPreservesPunctuationAndExcludesMisc() throws Exception {
        Card card = card("Name, \"quotes\"\n日本語", "Artist, \"quoted\"");
        Card misc = new Card("Miscellaneous", "MISC", "1");
        var service = new TradeReceivingExportService(directory, ignored -> fail("Inventory must not publish a trade"));
        String path = service.exportToInventoryFormat(List.of(new TradeItem(card, false), new TradeItem(misc, false)),
                List.of("NM", "NM"), List.of(4, 2));
        var rows = parse(Files.readString(Path.of(path)));
        assertEquals(1, rows.size());
        assertEquals(List.of("TST 12", card.getName(), card.getArtist(), "", "4"), rows.getFirst().toList());
    }

    @ParameterizedTest
    @CsvSource({
            "PWAR, 220s, F, WAR 220F",
            "pwar, 220s★, F, WAR 220F",
            "PEOE, 210p, '', EOE 210F",
            "PEOE, 210s, F, EOE 210F",
            "PDMU, 1, F, DMU 1F",
            "PCHK, 1★, F, COK 1F",
            "WAR, 220, '', WAR 220",
            "PLST, ARB-73★, E, ARB 73★E",
            "PCY, 12, F, PCY 12F",
            "PIP, 12, S, PIP 12S",
            "PRM, 12, F, PRM 12F",
            "PEOE, 210a, F, PEOE 210aF"
    })
    void tradeExportsMapSetPromosToBaseFoilsWithoutChangingTheTrade(String set,String number,String finish,String code) throws Exception {
        Card card=card("Example card","Artist");card.setSetCode(set);card.setCollectorNumber(number);
        var item=new TradeItem(card,!finish.isEmpty(),2,finish);
        var identity=item.getCard().identity();var originalPrice=item.getUnitPrice();
        var service=new TradeReceivingExportService(directory,ignored -> {});
        String received=service.exportToPOSFormat(List.of(item),"Trader","Customer",
                List.of(new BigDecimal("12.34")),List.of(2),"credit");
        var row=parse(Files.readString(Path.of(received))).get(1);
        assertEquals(code,row.get(4));assertEquals("2",row.get(9));
        assertEquals("6.17",row.get(13));assertEquals("12.34",row.get(16));assertEquals("12.34",row.get(18));
        if (code.endsWith("F")) assertEquals("Example card (Foil)",row.get(7));
        String inventory=service.exportToInventoryFormat(List.of(item),List.of("NM"),List.of(2));
        assertEquals(List.of(code,"Example card","Artist","","2"),
                parse(Files.readString(Path.of(inventory))).getFirst().toList());
        assertEquals(identity,item.getCard().identity());assertEquals(finish,item.getFinishType());
        assertEquals(originalPrice,item.getUnitPrice());
    }

    @Test void promoAndBaseFoilsCanShareStockWithoutLosingQuantitiesOrCosts() throws Exception {
        Card promo=card("Example card","Artist");promo.setSetCode("PEOE");promo.setCollectorNumber("210p");promo.setProviderId("promo");
        Card prerelease=promo.copy();prerelease.setCollectorNumber("210s");prerelease.setProviderId("prerelease");
        Card base=promo.copy();base.setSetCode("EOE");base.setCollectorNumber("210");base.setProviderId("base");
        var items=List.of(new TradeItem(promo,false),new TradeItem(base,true),new TradeItem(prerelease,true));
        var service=new TradeReceivingExportService(directory,ignored -> {});
        String received=service.exportToPOSFormat(items,"Trader","Customer",
                List.of(new BigDecimal("1.50"),new BigDecimal("2.00"),new BigDecimal("2.50")),List.of(2,3,4),"credit");
        var rows=parse(Files.readString(Path.of(received)));
        assertEquals(4,rows.size());
        assertTrue(rows.subList(1,4).stream().allMatch(row -> row.get(4).equals("EOE 210F")));
        assertEquals(List.of("1.50","3.00","5.00"),rows.subList(1,4).stream().map(row -> row.get(16)).toList());
        String inventory=service.exportToInventoryFormat(items,List.of("NM","NM","LP"),List.of(2,3,4));
        var inventoryRows=parse(Files.readString(Path.of(inventory)));
        assertEquals(1,inventoryRows.size());assertEquals("EOE 210F",inventoryRows.getFirst().get(0));
        assertEquals("9",inventoryRows.getFirst().get(4));
    }

    @Test void promoAliasDoesNotHideUnrelatedPrintingCollisions() {
        Card promo=card("Example card","Artist");promo.setSetCode("PEOE");promo.setCollectorNumber("210p");
        Card base=promo.copy();base.setSetCode("EOE");base.setCollectorNumber("210");base.setProviderId("base");
        Card other=base.copy();other.setProviderId("different-printing");
        var service=new TradeReceivingExportService(directory,ignored -> fail("Must not publish a conflicting export"));
        var items=List.of(new TradeItem(promo,true),new TradeItem(base,true),new TradeItem(other,true));
        assertThrows(IllegalArgumentException.class,()->service.exportToPOSFormat(items,"Trader","Customer",
                List.of(BigDecimal.TEN,BigDecimal.TEN,BigDecimal.TEN),List.of(1,1,1),"credit"));
        assertThrows(IllegalArgumentException.class,()->service.exportToInventoryFormat(items,List.of("NM","NM","NM"),List.of(1,1,1)));
        other.setName("Different card");
        var differentNames=List.of(new TradeItem(promo,true),new TradeItem(other,true));
        assertThrows(IllegalArgumentException.class,()->service.exportToPOSFormat(differentNames,"Trader","Customer",
                List.of(BigDecimal.TEN,BigDecimal.TEN),List.of(1,1),"credit"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "F", "E", "S"})
    void listAndOriginalPrintingsCanShareStockWithoutLosingQuantitiesOrCosts(String finish) throws Exception {
        Card original=card("Example card","Artist");original.setSetCode("C17");original.setCollectorNumber("149");original.setProviderId("original");
        Card listed=original.copy();listed.setSetCode("PLST");listed.setCollectorNumber("C17-149");listed.setProviderId("listed");
        var service=new TradeReceivingExportService(directory,ignored -> {});
        for (var cards : List.of(List.of(original,listed),List.of(listed,original))) {
            var items=cards.stream().map(card -> new TradeItem(card,!finish.isEmpty(),1,finish)).toList();
            var identities=items.stream().map(item -> item.getCard().identity()).toList();
            var prices=items.stream().map(TradeItem::getUnitPrice).toList();
            String received=service.exportToPOSFormat(items,"Trader","Customer",
                    List.of(new BigDecimal("1.50"),new BigDecimal("2.50")),List.of(2,3),"credit");
            var rows=parse(Files.readString(Path.of(received)));
            assertEquals(3,rows.size());
            assertEquals(List.of("C17 149"+finish,"C17 149"+finish),rows.subList(1,3).stream().map(row -> row.get(4)).toList());
            assertEquals(List.of("2","3"),rows.subList(1,3).stream().map(row -> row.get(9)).toList());
            assertEquals(List.of("1.50","3.75"),rows.subList(1,3).stream().map(row -> row.get(16)).toList());
            assertEquals(List.of("1.50","2.50"),rows.subList(1,3).stream().map(row -> row.get(18)).toList());
            String inventory=service.exportToInventoryFormat(items,List.of("NM","LP"),List.of(2,3));
            var inventoryRows=parse(Files.readString(Path.of(inventory)));
            assertEquals(1,inventoryRows.size());
            assertEquals(List.of("C17 149"+finish,"Example card","Artist","","5"),inventoryRows.getFirst().toList());
            assertEquals(identities,items.stream().map(item -> item.getCard().identity()).toList());
            assertEquals(prices,items.stream().map(TradeItem::getUnitPrice).toList());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "language", "original identity", "list identity", "finish"})
    void listAliasDoesNotHideUnrelatedPrintingCollisions(String conflict) {
        Card original=card("Example card","Artist");original.setSetCode("C17");original.setCollectorNumber("149");original.setProviderId("original");
        Card listed=original.copy();listed.setSetCode("PLST");listed.setCollectorNumber("C17-149");listed.setProviderId("listed");
        var items=new ArrayList<TradeItem>();
        switch (conflict) {
            case "name" -> listed.setName("Different card");
            case "language" -> listed.setLanguage("ja");
            case "original identity", "list identity" -> {
                Card other=(conflict.equals("original identity") ? original : listed).copy();
                other.setProviderId("unrelated-printing");
                items.add(new TradeItem(other,false));
            }
            // A literal collector suffix must not be confused with a foil finish.
            case "finish" -> listed.setCollectorNumber("C17-149F");
            default -> throw new AssertionError(conflict);
        }
        items.add(new TradeItem(listed,false));
        items.add(new TradeItem(original,conflict.equals("finish")));
        var service=new TradeReceivingExportService(directory,ignored -> fail("Must not publish a conflicting export"));
        var quantities=items.stream().map(item -> 1).toList();
        var error=assertThrows(IllegalArgumentException.class,()->service.exportToPOSFormat(items,"Trader","Customer",
                items.stream().map(item -> BigDecimal.TEN).toList(),quantities,"credit"));
        assertTrue(error.getMessage().contains("POS mapping merges different printings: C17 149"));
        assertThrows(IllegalArgumentException.class,()->service.exportToInventoryFormat(items,
                items.stream().map(item -> "NM").toList(),quantities));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void tradeNamesUsePosCommaSubstituteWithoutRequiringQuotedFields(boolean foil) throws Exception {
        String originalName="Koma, World-Eater, Test";
        Card card=card(originalName,"Artist");
        var service=new TradeReceivingExportService(directory,ignored -> {});
        var item=new TradeItem(card,foil);
        String filename=service.exportToPOSFormat(List.of(item),"Trader","Customer",
                List.of(new BigDecimal("12.34")),List.of(2),"credit");
        String csv=Files.readString(Path.of(filename));
        // The POS splits on commas rather than honoring a quoted name.
        String[] fields=csv.split("\\r\\n")[1].split(",",-1);
        assertEquals(19,fields.length);
        assertEquals("Komaɕ World-Eaterɕ Test"+(foil ? " ("+item.getFinish()+")" : ""),fields[7]);
        assertFalse(csv.contains("\""));
        assertFalse(csv.contains("&#"));
        assertEquals("2",fields[9]);assertEquals("",fields[12]);
        assertEquals("6.17",fields[13]);assertEquals("12.34",fields[16]);
        assertEquals("TAX",fields[17]);assertEquals("12.34",fields[18]);
        assertEquals(originalName,card.getName());
    }

    @Test
    void invoiceFileHasSixColumnsAndTotalsInTheRightPositions() throws Exception {
        Card card = card("Name, \"quoted\"", "Artist");
        new CsvExportService(directory).exportInvoiceToCsv(List.of(new OrderItem(card, false, 2), new OrderItem(card, true, 3)), "invoice.csv");
        var rows = parse(Files.readString(directory.resolve("invoice.csv")));
        assertEquals(4, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.size() == 6));
        assertEquals(card.getName(), rows.get(1).get(1));
        assertEquals(List.of("TOTAL", "", "", "5", "", "84.68"), rows.getLast().toList());
    }

    @Test
    void writeFailuresPropagateAndPreventPublishing() throws Exception {
        Writer failing = new Writer() {
            public void write(char[] value, int offset, int count) throws IOException { throw new IOException("Disk full"); }
            public void flush() {}
            public void close() {}
        };
        Card card = card("Name", "Artist");
        for (var format : CsvExportService.ExportFormat.values()) {
            assertThrows(IOException.class, () -> CardCsvEncoder.write(failing, List.of(new CardEntry(card, false)), format));
        }
        assertThrows(IOException.class, () -> CsvExportService.writeInvoice(failing, List.of(new OrderItem(card, false))));
        Path fileInsteadOfDirectory = Files.writeString(directory.resolve("blocked"), "not a directory");
        var service = new TradeReceivingExportService(fileInsteadOfDirectory, ignored -> fail("Must not publish a failed export"));
        assertThrows(IOException.class, () -> service.exportToPOSFormat(List.of(new TradeItem(card, false)), "Trader", "Customer",
                List.of(BigDecimal.TEN), List.of(1), "credit"));
        assertThrows(IOException.class, () -> new CsvExportService(fileInsteadOfDirectory)
                .exportCardsToCsv(List.of(card), "blocked.csv"));
    }

    @Test
    void zeroQuantityDoesNotRequireAPriceAndNullArtistIsEmpty() throws Exception {
        CardEntry entry = new CardEntry("TST 12", "Name", null, null, null);
        assertEquals(List.of("TST 12", "Name", "", "", "0"), parse(entry.toZeroOutItems()).getFirst().toList());
    }

    @Test
    void foilDetectionMatchesGeneratedCodesAndAcceptsLegacyUppercase() {
        assertTrue(new CardEntry(card("Name", "Artist"), true).isFoil());
        assertFalse(new CardEntry(card("Name", "Artist"), false).isFoil());
        assertTrue(new CardEntry("TST 12F", "Name", BigDecimal.ONE, "rare", "").isFoil());
    }
}
