package com.proxy.cache;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Short-lived memory that lets a PATCH-only LangSmith request be attributed to
 * the trace its POST created.
 *
 * LangSmith sends a run twice: a POST when the run starts (name, model, inputs,
 * n8n metadata) and a PATCH when it ends (outputs, token usage, end_time). The
 * PATCH body carries almost nothing — frequently not even {@code trace_id}. A
 * stateless proxy therefore cannot tell which trace, which Langfuse project or
 * which session the PATCH belongs to, so it invents a new trace and the outputs,
 * usage and latency never reach the observation the user is looking at.
 *
 * This cache remembers, per run id, the trace it belongs to and the descriptive
 * fields only the POST carried, so the PATCH can be merged onto the right trace
 * and routed to the right project.
 *
 * Entries are bounded by count and age; losing one only degrades a PATCH back to
 * the stateless behaviour, so eviction is always safe.
 */
@Component
public class TraceContextCache {

    private static final Logger logger = LoggerFactory.getLogger(TraceContextCache.class);

    /** Everything the POST knew about a trace, replayed onto later PATCHes. */
    public static class TraceContext {
        public String nodeName;
        public String sessionId;
        public String userId;
        public String name;
        public ObjectNode metadata;
        public final Set<String> tags = new LinkedHashSet<>();

        /** true when sessionId came from real n8n context, not a fallback guess. */
        public boolean sessionIdIsReliable;
    }

    /** The create-side fields of a single observation, replayed onto its PATCH. */
    public static class RunInfo {
        public String traceId;
        public String name;
        public String model;
        public String runType;
        public String startTime;
        public String parentRunId;
    }

    /** Session, user and project learned from one n8n execution. */
    public static class ExecutionContext {
        public String sessionId;
        public String userId;
        public String nodeName;
        /** One n8n execution serves one webhook call, so one message id. */
        public String msgId;
    }

    private final Map<String, ExecutionContext> executions;
    private final Map<String, Long>             executionSeenAt;
    private final Map<String, TraceContext> traces;
    private final Map<String, RunInfo>      runs;
    private final Map<String, Long>         traceSeenAt;
    private final Map<String, Long>         runSeenAt;
    private final long ttlMillis;

    public TraceContextCache(
            @Value("${proxy.trace-cache.max-entries:20000}") int maxEntries,
            @Value("${proxy.trace-cache.ttl-minutes:180}") long ttlMinutes) {

        this.ttlMillis   = ttlMinutes * 60_000L;
        this.traces      = lru(maxEntries);
        this.runs        = lru(maxEntries * 4);
        this.traceSeenAt = lru(maxEntries);
        this.runSeenAt   = lru(maxEntries * 4);
        this.executions      = lru(maxEntries);
        this.executionSeenAt = lru(maxEntries);

        logger.info("TraceContextCache: maxEntries={}, ttl={}min", maxEntries, ttlMinutes);
    }

    private static <V> Map<String, V> lru(int max) {
        return Collections.synchronizedMap(new LinkedHashMap<String, V>(16, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        });
    }

    private boolean expired(Map<String, Long> seenAt, String key) {
        Long t = seenAt.get(key);
        return t == null || (System.currentTimeMillis() - t) > ttlMillis;
    }

    // ── run → trace ─────────────────────────────────────────────────────────

    public void putRun(String runId, RunInfo info) {
        if (runId == null || info == null) return;
        runs.put(runId, info);
        runSeenAt.put(runId, System.currentTimeMillis());
    }

    public RunInfo getRun(String runId) {
        if (runId == null) return null;
        if (expired(runSeenAt, runId)) {
            runs.remove(runId);
            runSeenAt.remove(runId);
            return null;
        }
        return runs.get(runId);
    }

    /** True when this run id was already sent to Langfuse as an observation. */
    public boolean isKnownObservation(String runId) {
        return getRun(runId) != null;
    }

    public String getTraceIdForRun(String runId) {
        RunInfo info = getRun(runId);
        return info != null ? info.traceId : null;
    }

    // ── trace context ───────────────────────────────────────────────────────

    public TraceContext getTrace(String traceId) {
        if (traceId == null) return null;
        if (expired(traceSeenAt, traceId)) {
            traces.remove(traceId);
            traceSeenAt.remove(traceId);
            return null;
        }
        return traces.get(traceId);
    }

    public void putTrace(String traceId, TraceContext ctx) {
        if (traceId == null || ctx == null) return;
        traces.put(traceId, ctx);
        traceSeenAt.put(traceId, System.currentTimeMillis());
    }

    // ── n8n execution context ───────────────────────────────────────────────

    /**
     * n8n runs every AI node of one workflow run under a single execution id,
     * and injects it into LangSmith metadata. An n8n AI Agent node has no field
     * for run metadata and its model is wrapped in a proxy that discards any
     * set on it, so its trace arrives with no session and no project — but it
     * does arrive with that execution id. Whatever a sibling node in the same
     * execution knew can therefore be shared across all of them.
     */
    public ExecutionContext getExecution(String executionId) {
        if (executionId == null) return null;
        if (expired(executionSeenAt, executionId)) {
            executions.remove(executionId);
            executionSeenAt.remove(executionId);
            return null;
        }
        return executions.get(executionId);
    }

    /** Record what this execution knows, without unlearning anything. */
    public void mergeExecution(String executionId, String sessionId, String userId,
                               String nodeName, String msgId) {
        if (executionId == null) return;
        ExecutionContext ctx = executions.get(executionId);
        if (ctx == null || expired(executionSeenAt, executionId)) ctx = new ExecutionContext();
        if (sessionId != null && !sessionId.isEmpty()) ctx.sessionId = sessionId;
        if (userId    != null && !userId.isEmpty())    ctx.userId    = userId;
        if (nodeName  != null && !nodeName.isEmpty())  ctx.nodeName  = nodeName;
        if (msgId     != null && !msgId.isEmpty())     ctx.msgId     = msgId;
        executions.put(executionId, ctx);
        executionSeenAt.put(executionId, System.currentTimeMillis());
    }

    public int traceCount() { return traces.size(); }
    public int runCount()   { return runs.size(); }
}
