package com.proxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Delivers transformed batches to Langfuse off the request thread, so n8n is
 * answered as soon as the batch has been accepted rather than after the
 * Langfuse round trip.
 *
 * Only delivery is deferred. Transformation stays on the request thread because
 * it updates the correlation cache — which trace a run belongs to, which tool an
 * agent asked for — and those decisions depend on the order the requests
 * arrived in. Moving that off-thread would make correlation racy.
 *
 * Batches are partitioned across workers by target project, and each worker is
 * single-threaded, so a project's batches are delivered in the order they were
 * transformed. That matters because Langfuse merges events last-non-null-wins:
 * reordering a trace's batches could let an earlier value overwrite a later one.
 */
@Component
public class LangfuseDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(LangfuseDispatcher.class);

    /** One transformed batch, waiting for its turn on the wire. */
    private static final class Delivery {
        final JsonNode payload;
        final String   nodeName;
        int attempts;

        Delivery(JsonNode payload, String nodeName) {
            this.payload  = payload;
            this.nodeName = nodeName;
        }
    }

    private final LangfuseService langfuseService;

    private final int  workerCount;
    private final int  queueCapacity;
    private final int  maxRetries;
    private final long retryBackoffMillis;
    private final long offerTimeoutMillis;
    private final long shutdownDrainSeconds;

    private final List<BlockingQueue<Delivery>> queues = new ArrayList<>();
    private final List<Thread>                  workers = new ArrayList<>();
    private volatile boolean running = true;

    private final AtomicLong accepted  = new AtomicLong();
    private final AtomicLong delivered = new AtomicLong();
    private final AtomicLong failed    = new AtomicLong();
    private final AtomicLong dropped   = new AtomicLong();

    @Autowired
    public LangfuseDispatcher(
            LangfuseService langfuseService,
            @Value("${proxy.async.workers:4}") int workerCount,
            @Value("${proxy.async.queue-capacity:5000}") int queueCapacity,
            @Value("${proxy.async.max-retries:3}") int maxRetries,
            @Value("${proxy.async.retry-backoff-millis:500}") long retryBackoffMillis,
            @Value("${proxy.async.offer-timeout-millis:1000}") long offerTimeoutMillis,
            @Value("${proxy.async.shutdown-drain-seconds:20}") long shutdownDrainSeconds) {

        this.langfuseService      = langfuseService;
        this.workerCount          = Math.max(1, workerCount);
        this.queueCapacity        = Math.max(1, queueCapacity);
        this.maxRetries           = Math.max(0, maxRetries);
        this.retryBackoffMillis   = retryBackoffMillis;
        this.offerTimeoutMillis   = offerTimeoutMillis;
        this.shutdownDrainSeconds = shutdownDrainSeconds;

        for (int i = 0; i < this.workerCount; i++) {
            BlockingQueue<Delivery> q = new ArrayBlockingQueue<>(this.queueCapacity);
            queues.add(q);
            Thread t = new Thread(() -> drain(q), "langfuse-dispatch-" + i);
            t.setDaemon(false);   // so shutdown waits for in-flight batches
            workers.add(t);
            t.start();
        }
        logger.info("LangfuseDispatcher: {} worker(s), queue capacity {} each, {} retries",
                    this.workerCount, this.queueCapacity, this.maxRetries);
    }

    /**
     * Queue a batch for delivery.
     *
     * @return false when the batch could not be queued, so the caller can tell
     *         n8n the trace was not accepted rather than silently losing it.
     */
    public boolean submit(JsonNode payload, String nodeName) {
        if (!running) {
            logger.warn("Dispatcher is shutting down; refusing batch for node='{}'", nodeName);
            return false;
        }
        BlockingQueue<Delivery> q = queueFor(nodeName);
        try {
            // A bounded wait rather than an outright drop: a brief Langfuse
            // stall should slow the proxy, not discard traces.
            if (q.offer(new Delivery(payload, nodeName), offerTimeoutMillis, TimeUnit.MILLISECONDS)) {
                accepted.incrementAndGet();
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        dropped.incrementAndGet();
        logger.error("Langfuse delivery queue for node='{}' is full ({} entries); dropped a batch. "
                   + "Langfuse is not keeping up, or is down.", nodeName, queueCapacity);
        return false;
    }

    /** Same project every time, so its batches keep their transformed order. */
    private BlockingQueue<Delivery> queueFor(String nodeName) {
        String key = nodeName == null ? "" : nodeName;
        return queues.get(Math.abs(key.hashCode() % queues.size()));
    }

    private void drain(BlockingQueue<Delivery> q) {
        while (running || !q.isEmpty()) {
            Delivery d;
            try {
                d = q.poll(250, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (d == null) continue;
            deliver(d);
        }
    }

    private void deliver(Delivery d) {
        while (true) {
            boolean ok;
            try {
                ok = langfuseService.sendToLangfuse(d.payload, d.nodeName);
            } catch (Exception e) {
                logger.error("Error delivering batch to Langfuse (node='{}'): {}", d.nodeName, e.getMessage(), e);
                ok = false;
            }
            if (ok) { delivered.incrementAndGet(); return; }

            if (d.attempts++ >= maxRetries) {
                failed.incrementAndGet();
                logger.error("Giving up on a batch for node='{}' after {} attempt(s); those events are lost.",
                             d.nodeName, d.attempts);
                return;
            }
            try {
                Thread.sleep(retryBackoffMillis * d.attempts);   // linear backoff
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Queue depth, for /health and for spotting a Langfuse stall. */
    public int queueDepth() {
        int n = 0;
        for (BlockingQueue<Delivery> q : queues) n += q.size();
        return n;
    }

    public long acceptedCount()  { return accepted.get(); }
    public long deliveredCount() { return delivered.get(); }
    public long failedCount()    { return failed.get(); }
    public long droppedCount()   { return dropped.get(); }

    /**
     * Finish what is queued before the process exits — otherwise a redeploy
     * silently loses every batch still in flight.
     */
    @PreDestroy
    public void shutdown() {
        logger.info("Draining {} queued batch(es) before shutdown", queueDepth());
        running = false;
        long deadline = System.currentTimeMillis() + shutdownDrainSeconds * 1000L;
        for (Thread t : workers) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            try { t.join(left); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        int left = queueDepth();
        if (left > 0) logger.error("Shutdown drain timed out with {} batch(es) undelivered", left);
        logger.info("Dispatcher stopped: accepted={}, delivered={}, failed={}, dropped={}",
                    accepted.get(), delivered.get(), failed.get(), dropped.get());
    }
}
