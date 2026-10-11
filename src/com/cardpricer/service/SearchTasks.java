package com.cardpricer.service;

import java.util.concurrent.*;
import java.util.function.Supplier;

/** Interactive local work never queues behind downloads or blocked provider requests. */
public final class SearchTasks {
    private SearchTasks() {}
    private static final Executor LOCAL=pool("card-search-local",2,32), ONLINE=pool("card-search-online",2,16);
    private static Executor pool(String name,int threads,int capacity) {
        return new ThreadPoolExecutor(threads,threads,30,TimeUnit.SECONDS,new ArrayBlockingQueue<>(capacity),r -> {
            var thread=new Thread(r,name);thread.setDaemon(true);return thread;
        },new ThreadPoolExecutor.AbortPolicy());
    }
    public static <T> CompletableFuture<T> local(Supplier<T> task) { return submit(LOCAL,task); }
    public static <T> CompletableFuture<T> online(Supplier<T> task) { return submit(ONLINE,task); }
    private static <T> CompletableFuture<T> submit(Executor executor,Supplier<T> task) {
        try { return CompletableFuture.supplyAsync(task,executor); }
        catch(RejectedExecutionException failure) { return CompletableFuture.failedFuture(new IllegalStateException("Search is busy. Wait a moment and try again.",failure)); }
    }
}
