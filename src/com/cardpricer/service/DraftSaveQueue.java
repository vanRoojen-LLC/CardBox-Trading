package com.cardpricer.service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** One running write and at most one pending write per draft, with a fixed capacity. */
final class DraftSaveQueue {
    private record Save(Runnable write, java.util.function.Consumer<RuntimeException> completed) {}
    private final Map<UUID,Save> pending=new LinkedHashMap<>();
    private final Map<UUID,RuntimeException> failures=new HashMap<>();
    private final Executor executor;
    private final int capacity;
    private boolean running;

    DraftSaveQueue(Executor executor,int capacity) { this.executor=executor; this.capacity=capacity; }

    synchronized void submit(UUID id,Runnable write,java.util.function.Consumer<RuntimeException> completed) {
        if(!pending.containsKey(id) && pending.size()>=capacity)
            throw new IllegalStateException("Draft storage is busy. Wait for saving to finish before opening another draft.");
        pending.put(id,new Save(write,completed));
        if(!running) {
            running=true;
            try { executor.execute(this::drain); }
            catch(RuntimeException failure) { running=false; pending.remove(id); throw failure; }
        }
    }
    private void drain() {
        while(true) {
            UUID id; Save save;
            synchronized(this) {
                if(pending.isEmpty()) { running=false; notifyAll(); return; }
                id=pending.keySet().iterator().next(); save=pending.remove(id);
            }
            RuntimeException failure=null;
            try { save.write().run(); } catch(RuntimeException error) { failure=error; }
            synchronized(this) {
                if(failure==null) failures.remove(id); else failures.put(id,failure);
            }
            try { save.completed().accept(failure); } catch(RuntimeException ignored) { /* UI delivery cannot stop persistence. */ }
        }
    }
    synchronized void flush(Duration timeout) {
        awaitIdle(timeout);
        if(!failures.isEmpty()) throw failures.values().iterator().next();
    }
    synchronized void discardFailure(UUID id,Duration timeout) {
        awaitIdle(timeout);
        failures.remove(id);
    }
    private void awaitIdle(Duration timeout) {
        long deadline=System.nanoTime()+timeout.toNanos();
        while(running) {
            long remaining=deadline-System.nanoTime();
            if(remaining<=0) throw new IllegalStateException("Draft save did not finish. Keep the app open and retry.");
            try { TimeUnit.NANOSECONDS.timedWait(this,remaining); }
            catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Draft save interrupted",interrupted); }
        }
    }
}
