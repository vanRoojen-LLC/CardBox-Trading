package com.cardpricer.gui.panel.trade;
import com.cardpricer.model.Card;
import com.cardpricer.model.ParsedCode;
import com.cardpricer.service.*;
import javax.swing.SwingUtilities;
import java.util.concurrent.CompletableFuture;
import com.cardpricer.util.CardCodeParser;
import java.util.function.Consumer;

/** Only the current request updates the editor; Enter can reuse a pending preview. */
public final class TradeEntryPresenter implements AutoCloseable {
    private final CardRepository catalog;
    private final CardSearchService search;
    private CompletableFuture<Card> pending;
    private String pendingKey;
    private Consumer<Card> success;
    private Consumer<Throwable> failure;
    private boolean delivered;
    private long generation;
    public TradeEntryPresenter(CardRepository catalog,ScryfallApiService api) {this.catalog=catalog;search=api.interactiveSearch(catalog);}
    public void lookup(ParsedCode code,Consumer<Card> success,Consumer<Throwable> failure) {
        String key=CardCodeParser.format(code)+"@"+catalog.generation();
        if(key.equals(pendingKey) && pending!=null && !pending.isCancelled() && !delivered) {
            this.success=success;this.failure=failure;return;
        }
        cancel();long request=generation;pendingKey=key;delivered=false;
        this.success=success;this.failure=failure;
        pending=search.lookup(code);
        pending.whenComplete((card,error)->SwingUtilities.invokeLater(()-> {
            if(request!=generation) return;
            delivered=true;
            if(error==null) this.success.accept(card);else this.failure.accept(cause(error));
        }));
    }
    public static boolean hasPrice(Card card,String finish) {
        return CardSearchService.hasPrice(card,finish);
    }
    private static Throwable cause(Throwable error) { while(error.getCause()!=null) error=error.getCause();return error; }
    public void cancel() {generation++;pendingKey=null;if(pending!=null)pending.cancel(false);}
    public void close() {cancel();}
}
