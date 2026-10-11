package com.cardpricer.service;
import com.cardpricer.model.Card;
import com.cardpricer.util.AppDataDirectory;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;
class CatalogRecoveryTest {
    @Test void legacyCollapsedPrintingsAreSkippedWhileUnambiguousCacheStaysAvailable() throws Exception {
        Path cache=AppDataDirectory.cache().toPath().resolve("catalog.ndjson.gz");
        var catalog=ScryfallCatalogService.getInstance();
        String content="{\"nm\":\"First\",\"s\":\"TST\",\"n\":\"1\",\"p\":\"10\"}\n"
                +"{\"nm\":\"Other printing\",\"s\":\"TST\",\"n\":\"1\",\"p\":\"8\"}\n"
                +"{\"nm\":\"Known\",\"s\":\"TST\",\"n\":\"2\",\"p\":\"5\"}\n";
        try {
            try(var gzip=new GZIPOutputStream(Files.newOutputStream(cache))){gzip.write(content.getBytes(StandardCharsets.UTF_8));}
            assertEquals(1,catalog.loadFromDisk());assertTrue(catalog.lookup("TST","1").isEmpty());
            assertEquals("Known",catalog.lookup("TST","2").orElseThrow().getName());assertEquals(1,catalog.getAmbiguousLegacyKeys());
        }finally{Files.deleteIfExists(cache);catalog.invalidate();}
    }
    @Test void exactCollectorKeysAndLastGoodIndexSurviveCorruptCache() throws Exception {
        Path cache=AppDataDirectory.cache().toPath().resolve("catalog.ndjson.gz");
        var catalog=ScryfallCatalogService.getInstance();
        Card ordinary=new Card("Ordinary","TST","73");ordinary.setRarity("rare");ordinary.setPrice("10");ordinary.setProviderId("one");
        Card special=ordinary.copy();special.setCollectorNumber("73★");special.setProviderId("two");
        String content=ProviderCardMapper.toJson(ordinary)+"\n"+ProviderCardMapper.toJson(special)+"\n";
        try {
            try(var gzip=new GZIPOutputStream(Files.newOutputStream(cache))) {gzip.write(content.getBytes(StandardCharsets.UTF_8));}
            assertEquals(2,catalog.loadFromDisk());
            long generation=catalog.generation();
            assertEquals(2,catalog.searchNames("ordinary","",50).total());
            assertEquals("one",catalog.lookup("TST","73").orElseThrow().getProviderId());
            assertEquals("two",catalog.lookup("TST","73★").orElseThrow().getProviderId());
            catalog.lookup("TST","73").orElseThrow().setPrice("99");
            assertEquals("10",catalog.lookup("TST","73").orElseThrow().getPrice());
            byte[] valid=Files.readAllBytes(cache);Files.write(cache,java.util.Arrays.copyOf(valid,valid.length-5));
            assertThrows(java.io.IOException.class,catalog::loadFromDisk);
            assertEquals(generation,catalog.generation());
            assertEquals(2,catalog.searchNames("ordinary","",50).total());
            assertEquals(2,catalog.getCardCount());assertTrue(catalog.lookup("TST","73★").isPresent());
            try(var gzip=new GZIPOutputStream(Files.newOutputStream(cache))) {gzip.write("{invalid}\n".getBytes(StandardCharsets.UTF_8));}
            assertThrows(java.io.IOException.class,catalog::loadFromDisk);assertEquals(2,catalog.getCardCount());
        } finally {Files.deleteIfExists(cache);catalog.invalidate();}
    }
}
