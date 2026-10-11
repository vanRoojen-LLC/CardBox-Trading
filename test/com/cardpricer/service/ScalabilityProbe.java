package com.cardpricer.service;

import com.cardpricer.gui.panel.TradePanel;
import com.cardpricer.gui.panel.trade.TradeTableModel;
import com.cardpricer.model.*;
import com.cardpricer.util.AtomicFiles;
import org.json.JSONObject;
import javax.swing.SwingUtilities;
import java.math.BigDecimal;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Optional synthetic capacity measurement. Run with an isolated dataDir and -Xmx512m. */
public final class ScalabilityProbe {
    private static double ms(long start) { return (System.nanoTime()-start)/1_000_000.0; }
    private static double p95(double[] samples) { Arrays.sort(samples);return samples[(int)Math.ceil(samples.length*.95)-1]; }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(System.getProperty("cardpricer.dataDir")),report=Path.of(args[0]);
        var result=new JSONObject().put("measuredAt",Instant.now().toString()).put("java",System.getProperty("java.version"))
                .put("os",System.getProperty("os.name")).put("heapLimitBytes",Runtime.getRuntime().maxMemory())
                .put("scope","Synthetic 10000 distinct trade lines and 100000 receipt summaries. Headless Swing EDT; no catalog, network, filesystem scan or POS import.");
        var capture=new AtomicReference<Supplier<TradeDraft>>();
        SwingUtilities.invokeAndWait(()-> {
            var panel=new TradePanel();
            try {
                var batching=TradePanel.class.getDeclaredField("batchingCards");batching.setAccessible(true);batching.setBoolean(panel,true);
                long start=System.nanoTime();
                for(int i=0;i<10_000;i++) {
                    var card=new Card("Probe card "+i,"TST",Integer.toString(i));card.setProviderId("probe-"+i);card.setRarity("rare");card.setPrice("10.00");
                    panel.addFetchedCard(card,"","TST");
                }
                batching.setBoolean(panel,false);
                var summary=TradePanel.class.getDeclaredMethod("refreshSummary");summary.setAccessible(true);summary.invoke(panel);
                result.put("add10000LinesMs",ms(start));
                var field=TradePanel.class.getDeclaredField("tableModel");field.setAccessible(true);var table=(TradeTableModel)field.get(panel);
                var method=TradePanel.class.getDeclaredMethod("captureDraft");method.setAccessible(true);
                start=System.nanoTime();capture.set((Supplier<TradeDraft>)method.invoke(panel));result.put("initialEdtCaptureMs",ms(start));
                double[] edits=new double[50],captures=new double[50];
                for(int i=0;i<50;i++) {
                    start=System.nanoTime();table.setValueAt(i+1,5000,4);edits[i]=ms(start);
                    start=System.nanoTime();capture.set((Supplier<TradeDraft>)method.invoke(panel));captures[i]=ms(start);
                }
                result.put("editAndSummaryP95Ms",p95(edits)).put("edtCaptureP95Ms",p95(captures));
            } catch(Exception failure) { throw new IllegalStateException(failure); }
            finally { panel.disposeResources(); }
        });
        long start=System.nanoTime();var draft=capture.get().get();
        var repo=new TradeRepository(root.resolve("ledger/trades.sqlite"));repo.saveRecoveryDraft(draft,null);
        result.put("initialEncodeAndDurableDraftMs",ms(start));
        double[] saves=new double[10],commits=new double[10];
        for(int i=0;i<saves.length;i++) {
            start=System.nanoTime();repo.saveRecoveryDraft(draft,null);saves[i]=ms(start);
        }
        result.put("durableDraftP95Ms",p95(saves));
        var coldCommit=new TradeDraft(UUID.randomUUID(),1,draft.trader(),draft.customer(),"","",draft.payment(),draft.credit(),draft.check(),draft.lines());
        start=System.nanoTime();repo.commit(coldCommit,TradeApplicationService.outputs(coldCommit,false));result.put("firstLocalCommitMs",ms(start));
        for(int i=0;i<commits.length;i++) {
            var committed=new TradeDraft(UUID.randomUUID(),1,draft.trader(),draft.customer(),"","",draft.payment(),draft.credit(),draft.check(),draft.lines());
            start=System.nanoTime();repo.commit(committed,TradeApplicationService.outputs(committed,false));commits[i]=ms(start);
        }
        result.put("localCommitP95Ms",p95(commits));
        Path local=root.resolve("history-probe/trades");var history=TradeHistoryService.repository(local.toString());
        start=System.nanoTime();
        try(var c=history.connect()) {
            c.setAutoCommit(false);
            for(int i=0;i<100_000;i++) {
                var r=new TradeRecord(local.resolve(i+".txt").toString(),LocalDateTime.of(2020,1,1,0,0).plusSeconds(i),"Customer "+i,"Staff","Check",new BigDecimal("10.01"),3);
                HistoryIndex.put(c,"file:"+r.filename,HistoryIndex.scope(local),"receipt","1",new HistoryIndex.Loaded(r,i==0 ? "Unique Lotus" : "Ordinary receipt card body"));
            }
            c.commit();
        }
        result.put("seed100000SummariesMs",ms(start));
        var session=new TradeHistoryService.Session(local.toString(),"");double[] pages=new double[20],searches=new double[20];
        for(int i=0;i<20;i++) {
            start=System.nanoTime();var page=session.page(new TradeHistoryService.Filter("","All",0),100,null,"");pages[i]=ms(start);
            if(page.count()!=100_000 || page.snapshot().records().size()!=100) throw new AssertionError("Unexpected history page");
            start=System.nanoTime();var search=session.page(new TradeHistoryService.Filter("Unique Lotus","All",0),100,null,"");searches[i]=ms(start);
            if(search.count()!=1) throw new AssertionError("Unexpected search result");
        }
        result.put("historyFirst100P95Ms",p95(pages)).put("historySearchP95Ms",p95(searches)).put("passed",true);
        AtomicFiles.write(report,result.toString(2));System.out.println(result.toString(2));
    }
}
