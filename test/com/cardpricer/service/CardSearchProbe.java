package com.cardpricer.service;

import com.cardpricer.gui.dialog.CardSearchPresenter;
import com.cardpricer.util.AtomicFiles;
import org.json.*;
import javax.swing.SwingUtilities;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Optional measurement against an existing catalog, copied into an isolated dataDir. No network. */
public final class CardSearchProbe {
    private static double ms(long start) {return (System.nanoTime()-start)/1_000_000.0;}
    private static double p95(double[] samples) {Arrays.sort(samples);return samples[(int)Math.ceil(samples.length*.95)-1];}
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]),root=Path.of(System.getProperty("cardpricer.dataDir")),report=Path.of(args[1]);
        Files.createDirectories(root.resolve("cache"));Files.copy(source,root.resolve("cache/catalog.ndjson.gz"),StandardCopyOption.REPLACE_EXISTING);
        var memory=ManagementFactory.getMemoryMXBean();var peak=new AtomicLong();var running=new AtomicBoolean(true);
        var sampler=new Thread(()-> {while(running.get()) {
            peak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(),Math::max);
            try {Thread.sleep(5);}catch(InterruptedException interrupted){return;}
        }},"search-memory-probe");sampler.setDaemon(true);sampler.start();
        var result=new JSONObject().put("measuredAt",java.time.Instant.now()).put("java",System.getProperty("java.version"))
                .put("heapLimitBytes",Runtime.getRuntime().maxMemory()).put("catalogBytes",Files.size(source))
                .put("catalogSha256",AtomicFiles.hash(Files.readAllBytes(source)))
                .put("scope","Existing downloaded catalog; load/reload, uncached indexed name queries and headless Swing debounce/result delivery. No network or rendered window.");
        try {
            var catalog=ScryfallCatalogService.getInstance();long start=System.nanoTime();catalog.loadFromDisk();
            result.put("initialLoadAndIndexMs",ms(start)).put("catalogPrintings",catalog.getCardCount()).put("ambiguousLegacyCodes",catalog.getAmbiguousLegacyKeys());
            System.gc();result.put("loadedHeapBytes",memory.getHeapMemoryUsage().getUsed());
            start=System.nanoTime();catalog.loadFromDisk();result.put("reloadAndIndexMs",ms(start));
            var queries=List.of("Sol Ring","lightning bolt","ring","an","island","urzas","teferi","counterspell","black lotus");
            var measurements=new JSONArray();
            for(String query:queries) {
                double[] samples=new double[100];int matches=0;
                for(int i=0;i<samples.length;i++) {start=System.nanoTime();matches=catalog.searchNames(query,"",50).total();samples[i]=ms(start);}
                if(matches==0) throw new AssertionError("Benchmark query absent from catalog: "+query);
                measurements.put(new JSONObject().put("query",query).put("matches",matches).put("p95Ms",p95(samples)));
            }
            result.put("indexedQueries",measurements);
            var api=new ScryfallApiService(){@Override public JSONObject makeApiCall(String url){throw new AssertionError("Unexpected network request: "+url);}};
            var service=api.interactiveSearch(catalog);double[] deliveries=new double[40];
            var presenter=new CardSearchPresenter(service);
            try {
                for(int i=0;i<deliveries.length;i++) {
                    var completed=new CompletableFuture<Double>();String query=queries.get(i%queries.size());
                    SwingUtilities.invokeAndWait(()-> {
                        long began=System.nanoTime();
                        var timer=new javax.swing.Timer(CardSearchService.TYPING_DELAY_MS,e->presenter.search(query,"",false,found -> {
                            var model=new javax.swing.DefaultListModel<com.cardpricer.model.Card>();
                            model.addAll(found.cards().stream().map(com.cardpricer.model.ProviderPrinting::toCard).toList());
                            if(model.isEmpty()) completed.completeExceptionally(new AssertionError("Empty result"));else completed.complete(ms(began));
                        },completed::completeExceptionally));timer.setRepeats(false);timer.start();
                    });
                    deliveries[i]=completed.get(3,TimeUnit.SECONDS);
                }
            } finally {SwingUtilities.invokeAndWait(presenter::close);}
            result.put("debouncedResultDeliveryP95Ms",p95(deliveries)).put("sampledPeakHeapBytes",peak.get()).put("passed",true);
        } finally {running.set(false);sampler.interrupt();AtomicFiles.write(report,result.toString(2));}
        System.out.println(result.toString(2));
    }
}
