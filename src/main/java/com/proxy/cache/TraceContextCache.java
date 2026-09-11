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

        /** The logical user message this trace belongs to, if declared. */
        public String msgId;

        /** The n8n execution, used to group when nothing else was declared. */
        public String executionId;

        /**
         * Whether the trace's input has already been written.
         *
         * One user message is one trace, but it is assembled from several n8n
         * executions arriving as separate requests. Only the first of them holds
         * the user's question; a later agent's input is its own prompt, and
         * writing it would replace the question on the trace.
         */
        public boolean inputSet;

        /**
         * Whether THIS batch must not supply the trace input — distinct from
         * inputSet, which records that the input has already been written.
         *
         * A tool call adopted into the message carries the tool's argument, not
         * the user's question, so it must stay silent; but it must not claim the
         * input was set, or a tool call arriving before the message's own batch
         * would leave the trace input empty forever. Deliberately never copied
         * from a cached context: it describes one batch, not the trace.
         */
        public boolean suppressInput;
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

    // ── pending tool calls ──────────────────────────────────────────────────

    /** A tool an agent asked for, awaiting the run that executes it. */
    public static class PendingToolCall {
        public String traceId;
        public String msgId;
        public String sessionId;
        public String nodeName;
        public String userId;
        public long   requestedAt;
    }

    private final Map<String, java.util.Deque<PendingToolCall>> pendingTools = lru(20000);

    /**
     * Record that an agent asked for a tool, keyed by the tool's name.
     *
     * n8n executes a tool as its own LangSmith root run, in its own request,
     * with no execution id and no parent — so the only link back to the message
     * is the request the agent made for it.
     *
     * Requests are queued rather than overwritten: two chats calling the same
     * tool at the same time are genuinely ambiguous, and keeping only the most
     * recent silently hands both executions to the later one.
     */
    public void recordToolCall(String toolName, PendingToolCall call) {
        if (toolName == null || toolName.isEmpty() || call == null) return;
        call.requestedAt = System.currentTimeMillis();
        String key = toolName.toLowerCase();
        synchronized (pendingTools) {
            java.util.Deque<PendingToolCall> q = pendingTools.get(key);
            if (q == null) { q = new java.util.ArrayDeque<>(); pendingTools.put(key, q); }
            // One agent turn re-reports the same action across several runs.
            boolean seen = false;
            for (PendingToolCall p : q) {
                if (p.traceId != null && p.traceId.equals(call.traceId)) { p.requestedAt = call.requestedAt; seen = true; break; }
            }
            if (!seen) q.addLast(call);
            while (q.size() > 32) q.removeFirst();
        }
    }

    /**
     * The request this tool run is executing, or null if that cannot be known.
     *
     * Returns a trace only when every live request for the tool points at the
     * same one. With two chats calling the same tool inside the window there is
     * no honest way to tell which run belongs to which, and attributing it to
     * the wrong message is worse than leaving it standalone — a misattributed
     * tool call is silently wrong, an unattributed one is visibly missing.
     */
    public PendingToolCall claimToolCall(String toolName, long windowMillis) {
        if (toolName == null || toolName.isEmpty()) return null;
        long cutoff = System.currentTimeMillis() - windowMillis;
        synchronized (pendingTools) {
            java.util.Deque<PendingToolCall> q = pendingTools.get(toolName.toLowerCase());
            if (q == null) return null;
            q.removeIf(c -> c.requestedAt < cutoff);
            if (q.isEmpty()) return null;

            PendingToolCall first = q.peekFirst();
            for (PendingToolCall c : q) {
                boolean same = c.traceId == null ? first.traceId == null : c.traceId.equals(first.traceId);
                if (!same) return null;   // ambiguous: two messages want this tool
            }
            // Consume it. A request that stays queued after its run has been
            // matched keeps counting as a rival for the next message's tool
            // call, so within one busy window every later call looks ambiguous
            // and nothing is ever attributed.
            q.removeFirst();
            if (q.isEmpty()) pendingTools.remove(toolName.toLowerCase());
            return first;
        }
    }

    /**
     * The one execution active in the recent past, or null if that is ambiguous.
     *
     * n8n emits a tool call as its own LangSmith root run carrying no n8n
     * metadata at all — no execution id, no node, no parent — so nothing links
     * it to the message it served. Recency is the only signal left, and it is
     * only trustworthy when a single execution was in flight: with two
     * concurrent chats, attributing a tool call by time would put it on the
     * wrong message. Returning null in that case leaves the run where it is
     * rather than filing it somewhere plausible and wrong.
     */
    public ExecutionContext soleRecentExecution(long windowMillis) {
        long cutoff = System.currentTimeMillis() - windowMillis;
        String only = null;
        synchronized (executionSeenAt) {
            for (Map.Entry<String, Long> e : executionSeenAt.entrySet()) {
                if (e.getValue() == null || e.getValue() < cutoff) continue;
                if (only != null) return null;   // more than one candidate
                only = e.getKey();
            }
        }
        return only == null ? null : executions.get(only);
    }

    public int traceCount() { return traces.size(); }
    public int runCount()   { return runs.size(); }
}
