package com.cardpricer.service;

import com.cardpricer.gui.panel.trade.TradeEntryPresenter;
import com.cardpricer.model.Card;
import com.cardpricer.util.CardCodeParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.cardpricer.exception.ScryfallApiException;
import org.json.JSONObject;
import javax.swing.SwingUtilities;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TradeEntryPresenterTest {
    @Test void enterReusesPendingPreviewAndOnlyTheAddCallbackRuns() throws Exception {
        var started=new CountDownLatch(1);var release=new CountDownLatch(1);var delivered=new CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();var shown=new AtomicReference<String>();
        var api=new ScryfallApiService() {
            @Override public Card fetchCard(String set,String number) {
                calls.incrementAndGet();started.countDown();
                try { assertTrue(release.await(3,TimeUnit.SECONDS)); } catch(InterruptedException e) {throw new AssertionError(e);}
                Card card=new Card("Expected","TST","1");card.setRarity("rare");card.setPrice("1.00");return card;
            }
        };
        var presenter=new TradeEntryPresenter((set,number)->Optional.empty(),api);var code=CardCodeParser.parse("TST 1");
        try {
            SwingUtilities.invokeAndWait(()->presenter.lookup(code,c->shown.set("preview"),e->shown.set("error")));
            assertTrue(started.await(2,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->presenter.lookup(code,c->{shown.set("added");delivered.countDown();},e->delivered.countDown()));
            release.countDown();assertTrue(delivered.await(2,TimeUnit.SECONDS));
            assertEquals("added",shown.get());assertEquals(1,calls.get());
        } finally {release.countDown();SwingUtilities.invokeAndWait(presenter::close);}
    }
    @ParameterizedTest
    @CsvSource({
            "PEOE 210sf, peoe, 210s, F, true",
            "PEOE 210sf, peoe, 210s, F, false",
            "PEOE 210pf, peoe, 210p, F, true",
            "peoe 219pf, peoe, 219p, F, true",
            "peoe 219pf, peoe, 219p, F, false",
            "peoe 219sf, peoe, 219s, F, true",
            "peoe 219sf, peoe, 219s, F, false",
            "40k 129s, 40k, 129★, S, true",
            "40k 129s, 40k, 129★, S, false",
            "PIP 882s, pip, 882, S, true",
            "PIP 882s, pip, 882, S, false",
            "TST 73★f, tst, 73★, F, false"
    })
    void typedCodesResolveTheSamePrintingFromCatalogOrApi(String input,String set,String number,String finish,boolean cached) throws Exception {
        Card card=new Card("Expected printing",set.toUpperCase(),number);
        card.setProviderId("exact-printing");card.setFoilPrice("12.34");
        var seenCatalog=new AtomicReference<String>();var seenApi=new AtomicReference<String>();
        CardRepository catalog=(s,n)->{
            seenCatalog.set(s+"/"+n);
            return cached && s.equals(set) && n.equals(number) ? Optional.of(card.copy()) : Optional.empty();
        };
        var api=new ScryfallApiService() {
            @Override public JSONObject makeApiCall(String url) throws ScryfallApiException {
                String path=java.net.URI.create(url).getPath();seenApi.set(path);
                if (!path.equals("/cards/"+set+"/"+number)) throw new ScryfallApiException("Card or set not found");
                return ProviderCardMapper.toJson(card);
            }
        };
        var presenter=new TradeEntryPresenter(catalog,api);
        var result=new AtomicReference<Card>();var failure=new AtomicReference<Throwable>();
        var done=new CountDownLatch(1);
        try {
            var parsed=CardCodeParser.parse(CardCodeParser.format(CardCodeParser.parse(input)));
            assertEquals(finish,parsed.finish);
            SwingUtilities.invokeAndWait(()->presenter.lookup(parsed,c->{result.set(c);done.countDown();},e->{failure.set(e);done.countDown();}));
            assertTrue(done.await(3,TimeUnit.SECONDS));
            assertNull(failure.get());assertNotNull(result.get());
            assertEquals(card.identity(),result.get().identity());
            assertEquals(card.getFoilPrice(),result.get().getFoilPrice());
            assertEquals(set+"/"+number,seenCatalog.get());
            assertEquals(cached ? null : "/cards/"+set+"/"+number,seenApi.get());
        } finally {SwingUtilities.invokeAndWait(presenter::close);}
    }

    @Test void supersededAndDisposedRequestsCannotUpdateTheView() throws Exception {
        CountDownLatch firstStarted=new CountDownLatch(1),releaseFirst=new CountDownLatch(1),secondDone=new CountDownLatch(1);
        AtomicReference<String> shown=new AtomicReference<>();
        CardRepository cards=(set,number)->{
            if(number.equals("1")) {
                firstStarted.countDown();
                boolean released=false;
                while(!released)try{released=releaseFirst.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}
            }
            Card card=new Card(number,set,number);card.setPrice("1.00");return Optional.of(card);
        };
        var presenter=new TradeEntryPresenter(cards,new ScryfallApiService());
        try {
            SwingUtilities.invokeAndWait(()->presenter.lookup(CardCodeParser.parse("TST 1"),c->shown.set("stale"),e->shown.set("error")));
            assertTrue(firstStarted.await(3,TimeUnit.SECONDS));
            SwingUtilities.invokeAndWait(()->presenter.lookup(CardCodeParser.parse("TST 2"),c->{shown.set(c.getName());secondDone.countDown();},e->secondDone.countDown()));
            assertTrue(secondDone.await(3,TimeUnit.SECONDS));assertEquals("2",shown.get());
            releaseFirst.countDown();
            SwingUtilities.invokeAndWait(()->{
                presenter.lookup(CardCodeParser.parse("TST 3"),c->shown.set("disposed"),e->shown.set("error"));
                presenter.close();
            });
            // Queue a barrier after SwingWorker's deferred done delivery.
            CountDownLatch delivered=new CountDownLatch(1);
            SwingUtilities.invokeAndWait(()->{var timer=new javax.swing.Timer(150,e->delivered.countDown());timer.setRepeats(false);timer.start();});
            assertTrue(delivered.await(2,TimeUnit.SECONDS));assertEquals("2",shown.get());
        } finally {releaseFirst.countDown();SwingUtilities.invokeAndWait(presenter::close);}
    }
}
