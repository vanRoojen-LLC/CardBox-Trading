package com.cardpricer.gui.dialog;

import com.cardpricer.service.CardSearchService;
import javax.swing.SwingUtilities;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Request lifetime is independent of the dialog, so stale/disposed delivery can be tested headlessly. */
public final class CardSearchPresenter implements AutoCloseable {
    private final CardSearchService search;
    private CompletableFuture<CardSearchService.Result> pending;
    private long generation;
    public CardSearchPresenter(CardSearchService search) { this.search=search; }
    public void search(String text,String set,boolean online,Consumer<CardSearchService.Result> success,Consumer<Throwable> failure) {
        cancel();long request=generation;
        pending=search.search(text,set,online);
        pending.whenComplete((result,error)->SwingUtilities.invokeLater(()-> {
            if(request!=generation) return;
            if(error==null) success.accept(result);else failure.accept(cause(error));
        }));
    }
    private static Throwable cause(Throwable error) { while(error.getCause()!=null) error=error.getCause();return error; }
    public void cancel() { generation++;if(pending!=null) pending.cancel(false); }
    @Override public void close() { cancel(); }
}
