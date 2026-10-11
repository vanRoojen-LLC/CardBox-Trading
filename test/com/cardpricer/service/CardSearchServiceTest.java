package com.cardpricer.service;

import com.cardpricer.exception.ScryfallApiException;
import com.cardpricer.gui.dialog.CardSearchPresenter;
import com.cardpricer.model.*;
import com.cardpricer.util.CardCodeParser;
import org.json.*;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CardSearchServiceTest {
    static Card card(String name,String set,String number) {
        var card=new Card(name,set,number);card.setProviderId(set+"-"+number);card.setPrice("10.00");card.setFoilPrice("20.00");card.setRarity("rare");return card;
    }
    static class Catalog implements SearchableCardRepository {
        volatile long version=1;
        private volatile List<Card> cards;
        private volatile CardNameIndex index;
        Catalog(Card... cards) { replace(cards); }
        void replace(Card... cards) { this.cards=Arrays.stream(cards).map(Card::copy).toList();index=new CardNameIndex(this.cards.stream().map(ProviderPrinting::from).toList());version++; }
        public long generation() { return version; }
        public boolean isLoaded() { return true; }
        public Optional<Card> lookup(String set,String number) { return cards.stream().filter(c->set.equalsIgnoreCase(c.getSetCode()) && number.equalsIgnoreCase(c.getCollectorNumber())).findFirst().map(Card::copy); }
        public CardNameIndex.Matches searchNames(String query,String set,int limit) { return index.search(query,set,limit); }
    }
    private static JSONObject result(Card... cards) { return new JSONObject().put("data",new JSONArray(Arrays.stream(cards).map(ProviderCardMapper::toJson).toList())).put("total_cards",cards.length); }
    private static ScryfallApiService offline() { return new ScryfallApiService() {
        @Override public JSONObject makeApiCall(String url) { throw new AssertionError("Unexpected network lookup: "+url); }
    }; }

    @Test void localNamesRankExactMatchesHandlePunctuationAndPreserveEveryPrinting() throws Exception {
        var cards=new ArrayList<Card>();for(int i=0;i<70;i++) cards.add(card("Sol Ring","TST",Integer.toString(i)));
        cards.add(card("Sol Ring","LEA","123"));cards.add(card("Sol Ring Replica","ZZZ","1"));
        cards.add(card("Urza’s Saga","MH2","259"));cards.add(card("Éowyn, Shieldmaiden","LTC","1"));
        cards.add(card("Fire // Ice","MH2","290"));cards.add(card("B.F.M. (Big Furry Monster)","UGL","28"));
        var search=new CardSearchService(new Catalog(cards.toArray(Card[]::new)),offline());
        var found=search.search("Sol Ring","",false).get(2,TimeUnit.SECONDS);
        assertFalse(found.online());assertEquals(72,found.total());assertEquals(50,found.cards().size());
        assertTrue(found.cards().stream().allMatch(c->c.name().equals("Sol Ring")));
        assertEquals(50,found.cards().stream().map(ProviderPrinting::identity).distinct().count());
        assertEquals("LEA",search.search("sol ring","alpha",false).get().cards().getFirst().identity().set());
        for(String query:List.of("urzas","eowyn","ice fire","B.F.M. (Big Furry Monster)"))
            assertEquals(1,search.search(query,"",false).get().total(),query);
        var changed=card("Sol Ring","LEA","123");changed.setPrice("99.00");
        var catalog=new Catalog(changed);var service=new CardSearchService(catalog,offline());
        assertEquals("99.00",service.search("sol ring","",false).get().cards().getFirst().normalPrice());
        changed.setPrice("1.00");catalog.replace(changed);
        assertEquals("1.00",service.search("sol ring","",false).get().cards().getFirst().normalPrice(),"Refresh invalidates cached results");
    }

    @Test void codeSearchRetainsSurgeAndPromoCollectorsAndSelectedFinish() throws Exception {
        var search=new CardSearchService(new Catalog(card("Surge","40K","129★"),card("Normal","40K","129"),
                card("Promo","PEOE","219p"),card("Prerelease","PEOE","219s")),offline());
        for(var pair:List.of(new String[]{"40k 129s","129★","S"},new String[]{"peoe 219pf","219p","F"},new String[]{"peoe 219sf","219s","F"})) {
            var found=search.search(pair[0],"",false).get();
            assertEquals(pair[1],found.cards().getFirst().identity().collectorNumber());assertEquals(pair[2],found.suggestedFinish());
        }
        assertEquals("219s",search.search("219sf","peoe",false).get().cards().getFirst().identity().collectorNumber());
    }

    @Test void identicalOnlineRequestsAreSharedAndCachedWithoutSharingMutableCards() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var calls=new AtomicInteger();
        var api=new ScryfallApiService() {
            @Override public JSONObject makeApiCall(String url) {
                calls.incrementAndGet();started.countDown();await(release);
                var card=card("Missing card","TST","1");
                return url.contains("/cards/tst/1") ? ProviderCardMapper.toJson(card) : result(card);
            }
        };
        var catalog=new Catalog();var search=new CardSearchService(catalog,api);
        var first=search.search("Missing card","",false);
        try {
            assertTrue(started.await(2,TimeUnit.SECONDS));
            var second=search.search("Missing card","",false);first.cancel(false);release.countDown();
            assertEquals(1,second.get(2,TimeUnit.SECONDS).total());
            assertEquals(1,search.search("Missing card","",false).get().total());assertEquals(1,calls.get());
        } finally { release.countDown(); }
        var one=search.lookup(CardCodeParser.parse("TST 1")).get();one.setPrice("99");
        var two=search.lookup(CardCodeParser.parse("TST 1")).get();assertEquals("10.00",two.getPrice());
    }

    @Test void onlineOperatorsAndSetFiltersRemainOnlineAndFailuresDoNotTriggerFuzzySearch() throws Exception {
        var urls=new ArrayList<String>();
        var api=new ScryfallApiService() {
            @Override public JSONObject makeApiCall(String url) throws ScryfallApiException {
                synchronized(urls) { urls.add(URLDecoder.decode(url,StandardCharsets.UTF_8)); }
                throw new ScryfallApiException(503,"Provider unavailable");
            }
        };
        var search=new CardSearchService(new Catalog(card("Local card","LEA","1")),api);
        assertThrows(ExecutionException.class,()->search.search("t:creature OR t:artifact","alpha",false).get());
        assertEquals(1,urls.size());assertTrue(urls.getFirst().contains("(t:creature OR t:artifact) set:lea"));
        assertThrows(ExecutionException.class,()->search.search("t:creature OR t:artifact","alpha",false).get());
        assertEquals(2,urls.size(),"Failures are retried, never cached or masked as empty results");
        assertThrows(ExecutionException.class,()->search.search("Local card","",true).get(),"Explicit online search bypasses local matches");
        assertEquals(3,urls.size());
    }

    @Test void fuzzyFallbackResolvesTheRequestedSetAndOnlyRunsForNotFound() throws Exception {
        var calls=new AtomicInteger();
        var api=new ScryfallApiService() {
            @Override public JSONObject makeApiCall(String url) throws ScryfallApiException {
                int call=calls.incrementAndGet();
                if(call==1) throw new ScryfallApiException(404,"Not found");
                if(call==2) { assertTrue(url.contains("named?fuzzy="));return ProviderCardMapper.toJson(card("Sol Ring","LTC","1")); }
                assertTrue(URLDecoder.decode(url,StandardCharsets.UTF_8).contains("(!\"Sol Ring\") set:lea"));
                return result(card("Sol Ring","LEA","274"));
            }
        };
        var found=new CardSearchService(new Catalog(),api).search("sl rign","alpha",false).get(2,TimeUnit.SECONDS);
        assertEquals("LEA",found.cards().getFirst().identity().set());assertEquals(3,calls.get());
    }

    @Test void blockedOnlineRequestsCannotDelayLocalResultsOrDeliverStaleViews() throws Exception {
        var started=new CountDownLatch(2);var release=new CountDownLatch(1);
        var api=new ScryfallApiService() {
            @Override public JSONObject makeApiCall(String url) { started.countDown();await(release);return result(card("Late result","TST","1")); }
        };
        var service=new CardSearchService(new Catalog(card("Sol Ring","LEA","274")),api);
        var first=service.search("Missing one","",true);var second=service.search("Missing two","",true);
        var shown=new AtomicReference<String>();var displayed=new CountDownLatch(1);var presenter=new CardSearchPresenter(service);
        try {
            assertTrue(started.await(2,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()-> {
                presenter.search("Missing one","",true,r->shown.set("stale"),e->shown.set("error"));
                presenter.cancel(); // Typing invalidates the view before the debounce fires.
                presenter.search("sol ring","",false,r->{shown.set(r.cards().getFirst().name());displayed.countDown();},e->displayed.countDown());
            });
            assertTrue(displayed.await(2,TimeUnit.SECONDS));assertEquals("Sol Ring",shown.get());
            release.countDown();first.get(2,TimeUnit.SECONDS);second.get(2,TimeUnit.SECONDS);
            SwingUtilities.invokeAndWait(presenter::close);SwingUtilities.invokeAndWait(()->{});
            assertEquals("Sol Ring",shown.get());
        } finally { release.countDown();SwingUtilities.invokeAndWait(presenter::close); }
    }
    @Test void recentRequestStorageBoundsPendingWorkEvictsOldResultsAndRetriesFailures() throws Exception {
        var requests=new RecentSearches<String,String>(2,java.time.Duration.ofMinutes(1));
        var one=new CompletableFuture<String>();var two=new CompletableFuture<String>();
        var first=requests.get("one",()->one);var duplicate=requests.get("one",()-> {throw new AssertionError("Duplicate work");});
        var second=requests.get("two",()->two);
        assertThrows(ExecutionException.class,()->requests.get("three",()->CompletableFuture.completedFuture("three")).get());
        one.complete("one");two.completeExceptionally(new IllegalStateException("offline"));
        assertEquals("one",first.get());assertEquals("one",duplicate.get());assertThrows(ExecutionException.class,second::get);
        assertEquals("two",requests.get("two",()->CompletableFuture.completedFuture("two")).get());
        assertEquals("three",requests.get("three",()->CompletableFuture.completedFuture("three")).get());
        assertEquals("new one",requests.get("one",()->CompletableFuture.completedFuture("new one")).get(),"Least recently used result is evicted");
        var expired=new RecentSearches<String,String>(1,java.time.Duration.ZERO);
        assertEquals("old",expired.get("key",()->CompletableFuture.completedFuture("old")).get());
        assertEquals("fresh",expired.get("key",()->CompletableFuture.completedFuture("fresh")).get());
    }
    private static void await(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("Test request was never released"); }
        catch(InterruptedException failure) { Thread.currentThread().interrupt();throw new AssertionError(failure); }
    }
}
