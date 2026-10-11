package com.cardpricer.service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** Bounded immutable results and shared in-flight requests; failures are never cached. */
final class RecentSearches<K,V> {
    private record Saved<V>(V value,long expires) {}
    private final Map<K,Saved<V>> cache=new LinkedHashMap<>(16,.75f,true);
    private final Map<K,CompletableFuture<V>> pending=new HashMap<>();
    private final int capacity;
    private final long ttl;
    RecentSearches(int capacity,Duration ttl) { this.capacity=capacity;this.ttl=ttl.toNanos(); }
    synchronized CompletableFuture<V> get(K key,Supplier<CompletableFuture<V>> start) {
        var saved=cache.get(key);
        if(saved!=null && System.nanoTime()<saved.expires()) return CompletableFuture.completedFuture(saved.value());
        cache.remove(key);
        var existing=pending.get(key);if(existing!=null) return existing.copy();
        if(pending.size()>=capacity) return CompletableFuture.failedFuture(new IllegalStateException("Too many pending searches. Wait a moment and retry."));
        var shared=new CompletableFuture<V>();pending.put(key,shared);
        CompletableFuture<V> request;
        try { request=start.get(); } catch(RuntimeException failure) { request=CompletableFuture.failedFuture(failure); }
        request.whenComplete((value,failure)-> {
            synchronized(this) {
                pending.remove(key);
                if(failure==null) {
                    cache.put(key,new Saved<>(value,System.nanoTime()+ttl));
                    while(cache.size()>capacity) cache.remove(cache.keySet().iterator().next());
                }
            }
            if(failure==null) shared.complete(value);else shared.completeExceptionally(failure);
        });
        return shared.copy(); // A closed dialog must not cancel another subscriber's request.
    }
}
