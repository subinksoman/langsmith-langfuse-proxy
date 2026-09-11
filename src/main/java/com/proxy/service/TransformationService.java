package com.proxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.proxy.cache.TraceContextCache;
import com.proxy.config.LangfuseConfig;
import com.proxy.model.TransformationResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
public class TransformationService {

    private static final Logger logger = LoggerFactory.getLogger(TransformationService.class);

    /** Internal flag: this run reached us as a PATCH with no POST half. */
    private static final String PATCH_ONLY_MARKER = "__proxy_patch_only";

    private final ObjectMapper objectMapper;
    private final LangfuseConfig langfuseConfig;
    private final TraceContextCache traceContextCache;

    @Autowired
    public TransformationService(ObjectMapper objectMapper,
                                 LangfuseConfig langfuseConfig,
                                 TraceContextCache traceContextCache) {
        this.objectMapper      = objectMapper;
        this.langfuseConfig    = langfuseConfig;
        this.traceContextCache = traceContextCache;
    }

    /**
     * Convert one LangSmith ingestion request into one Langfuse batch per
     * target project.
     *
     * LangSmith reports a run twice. The POST carries the descriptive half —
     * name, model, inputs, n8n metadata, trace_id. The PATCH carries the
     * result half — outputs, token usage, end_time — and frequently nothing
     * else, not even trace_id. Because the two halves arrive as separate HTTP
     * requests, everything the PATCH lacks has to be recovered from
     * {@link TraceContextCache} or the PATCH lands on an invented trace and
     * the outputs never reach the observation the user is looking at.
     *
     * Runs are grouped by trace (a single request can carry several) so each
     * trace gets its own name, input, metadata and output rather than the
     * first run's values copied across all of them. Traces are then grouped by
     * n8n node, since each node routes to a different Langfuse project and one
     * payload can only carry one set of credentials.
     *
     * Within each batch the event order is trace-create → observations →
     * trace-create-final, so the trace output is written last and cannot be
     * overwritten by an earlier event in the same batch.
     */
    public List<TransformationResult> transformAll(JsonNode langsmithData) {
        try {
            // ── COLLECT post + patch ──────────────────────────────────────
            List<JsonNode>        postRuns     = new ArrayList<>();
            Map<String, JsonNode> patchUpdates = new LinkedHashMap<>();

            if (langsmithData.has("post") && langsmithData.get("post").isArray()) {
                for (JsonNode run : langsmithData.get("post")) postRuns.add(run);
            }
            if (langsmithData.has("patch") && langsmithData.get("patch").isArray()) {
                for (JsonNode patch : langsmithData.get("patch")) {
                    String runId = getTextValue(patch, "id");
                    if (runId != null) patchUpdates.put(runId, patch);
                }
            }

            // Merge each patch onto its post when both are in this request;
            // a patch with no matching post becomes a run in its own right.
            List<JsonNode> allRuns = new ArrayList<>();
            Set<String>    postIds = new HashSet<>();
            for (JsonNode run : postRuns) {
                String id = getTextValue(run, "id");
                postIds.add(id);
                allRuns.add(mergeRunWithPatch(run, patchUpdates.get(id)));
            }
            for (Map.Entry<String, JsonNode> e : patchUpdates.entrySet()) {
                if (postIds.contains(e.getKey())) continue;
                // Flag it here, where we still know the run arrived with no POST
                // half in this request. After cache backfill it is
                // indistinguishable from a complete run, and re-emitting a
                // create for it would overwrite the real one in Langfuse.
                ObjectNode patchOnly = e.getValue().deepCopy();
                patchOnly.put(PATCH_ONLY_MARKER, true);
                allRuns.add(patchOnly);
            }

            if (allRuns.isEmpty()) {
                logger.info("No runs found in request (post and patch both empty)");
                return Collections.emptyList();
            }

            // ── BACKFILL from cache ───────────────────────────────────────
            // A patch-only run has no run_type/name/start_time, which would
            // otherwise make it unclassifiable and give it a placeholder name.
            List<JsonNode> enriched = new ArrayList<>(allRuns.size());
            for (JsonNode run : allRuns) enriched.add(enrichFromCache(run));
            allRuns = enriched;

            Map<String, JsonNode> runMap = buildRunMap(allRuns);

            // ── RESOLVE the trace each run belongs to ─────────────────────
            Map<String, String> runTrace = resolveTraceIds(allRuns, runMap);

            // ── GROUP runs by trace ───────────────────────────────────────
            Map<String, List<JsonNode>> byTrace = new LinkedHashMap<>();
            for (JsonNode run : allRuns) {
                String traceId = runTrace.get(getTextValue(run, "id"));
                if (traceId == null) continue;
                byTrace.computeIfAbsent(traceId, k -> new ArrayList<>()).add(run);
            }

            if (byTrace.isEmpty()) {
                logger.info("No runs could be attributed to a trace");
                return Collections.emptyList();
            }

            // ── BUILD one batch per target project ────────────────────────
            Map<String, ArrayNode> byNode      = new LinkedHashMap<>();
            Map<String, String>    nodeDisplay = new LinkedHashMap<>();

            for (Map.Entry<String, List<JsonNode>> entry : byTrace.entrySet()) {
                String         traceId   = entry.getKey();
                List<JsonNode> traceRuns = entry.getValue();

                TraceContextCache.TraceContext ctx = buildTraceContext(traceId, traceRuns);
                traceContextCache.putTrace(traceId, ctx);

                String nodeKey = ctx.nodeName != null ? ctx.nodeName : "";
                ArrayNode batch = byNode.computeIfAbsent(nodeKey, k -> objectMapper.createArrayNode());
                nodeDisplay.putIfAbsent(nodeKey, ctx.nodeName);

                appendTraceEvents(traceId, traceRuns, ctx, runMap, runTrace, batch);
            }

            // ── WRAP each batch in a Langfuse ingestion payload ───────────
            List<TransformationResult> results = new ArrayList<>();
            for (Map.Entry<String, ArrayNode> entry : byNode.entrySet()) {
                ArrayNode batch = entry.getValue();
                if (batch.isEmpty()) continue;

                String nodeName = nodeDisplay.get(entry.getKey());

                LangfuseConfig.ProjectConfig project = langfuseConfig.getProjectForNode(nodeName);
                ObjectNode metadata = objectMapper.createObjectNode();
                metadata.put("batch_size",      batch.size());
                metadata.put("sdk_integration", "LANGCHAIN");
                metadata.put("sdk_version",     "proxy-2.0.1");
                metadata.put("sdk_variant",     "langsmith-proxy");
                metadata.put("public_key",      project.getPublicKey());
                metadata.put("sdk_name",        "langsmith-langfuse-proxy");
                if (nodeName != null) metadata.put("node", nodeName);

                ObjectNode payload = objectMapper.createObjectNode();
                payload.set("batch", batch);
                payload.set("metadata", metadata);

                results.add(new TransformationResult(payload, nodeName));
            }

            logger.info("Transformed {} run(s) into {} trace(s) across {} project batch(es)",
                        allRuns.size(), byTrace.size(), results.size());

            return results;

        } catch (Exception e) {
            logger.error("Error during transformation: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Backwards-compatible single-batch entry point. Returns the first batch
     * only, so callers that can handle per-project routing should prefer
     * {@link #transformAll(JsonNode)}.
     */
    public TransformationResult transform(JsonNode langsmithData) {
        List<TransformationResult> all = transformAll(langsmithData);
        return all.isEmpty() ? new TransformationResult(null, null) : all.get(0);
    }

    // ========================================================================
    // CACHE BACKFILL + TRACE RESOLUTION
    // ========================================================================

    /**
     * Fill in the fields a PATCH-only run is missing from what its POST told
     * us earlier. Without this a patch is an unclassifiable bag of outputs:
     * no run_type (so it cannot be routed to a generation or a span), no name
     * (so it shows up as "LLMGeneration"/"Span") and no start_time (so its
     * latency is computed from the time the proxy happened to see it).
     */
    private JsonNode enrichFromCache(JsonNode run) {
        String runId = getTextValue(run, "id");
        TraceContextCache.RunInfo info = traceContextCache.getRun(runId);
        if (info == null) return run;

        ObjectNode merged = run.deepCopy();
        if (getTextValue(merged, "run_type")      == null && info.runType     != null) merged.put("run_type",      info.runType);
        if (getTextValue(merged, "name")          == null && info.name        != null) merged.put("name",          info.name);
        if (getTextValue(merged, "start_time")    == null && info.startTime   != null) merged.put("start_time",    info.startTime);
        if (getTextValue(merged, "trace_id")      == null && info.traceId     != null) merged.put("trace_id",      info.traceId);
        if (getTextValue(merged, "parent_run_id") == null && info.parentRunId != null) merged.put("parent_run_id", info.parentRunId);
        if (info.model != null) {
            // Stash the model so extractModel() finds it without a POST body.
            ObjectNode extra = merged.has("extra") && merged.get("extra").isObject()
                    ? (ObjectNode) merged.get("extra") : objectMapper.createObjectNode();
            ObjectNode meta = extra.has("metadata") && extra.get("metadata").isObject()
                    ? (ObjectNode) extra.get("metadata") : objectMapper.createObjectNode();
            if (!meta.has("ls_model_name")) meta.put("ls_model_name", info.model);
            extra.set("metadata", meta);
            merged.set("extra", extra);
        }
        return merged;
    }

    /**
     * Work out which trace each run belongs to.
     *
     * A run that states its own trace_id is trusted. Everything else is a
     * patch whose trace has to be recovered — from the cache, from an
     * ancestor in this request, or from the fact that a run with no parent is
     * itself the root of its trace. Falling back to the run's own id (the old
     * behaviour) is the last resort, because for a child run that invents a
     * brand-new single-observation trace.
     */
    private Map<String, String> resolveTraceIds(List<JsonNode> allRuns, Map<String, JsonNode> runMap) {
        Map<String, String> resolved = new LinkedHashMap<>();

        // Pass 1 — the run says so.
        for (JsonNode run : allRuns) {
            String id      = getTextValue(run, "id");
            String traceId = getTextValue(run, "trace_id");
            if (id != null && traceId != null) resolved.put(id, traceId);
        }

        // Pass 2 — dotted_order encodes the ancestry as
        // "<start><root-uuid>.<start><child-uuid>...", so its first segment
        // names the trace root. LangSmith puts it on patches too, which makes
        // it the one cache-free way to place a patch on its trace.
        for (JsonNode run : allRuns) {
            String id = getTextValue(run, "id");
            if (id == null || resolved.containsKey(id)) continue;
            String root = traceIdFromDottedOrder(getTextValue(run, "dotted_order"));
            if (root != null) resolved.put(id, root);
        }

        // Pass 3 — we saw this run's POST earlier.
        for (JsonNode run : allRuns) {
            String id = getTextValue(run, "id");
            if (id == null || resolved.containsKey(id)) continue;
            String cached = traceContextCache.getTraceIdForRun(id);
            if (cached != null) resolved.put(id, cached);
        }

        // Pass 4 — inherit from an ancestor, in this request or in the cache.
        for (JsonNode run : allRuns) {
            String id = getTextValue(run, "id");
            if (id == null || resolved.containsKey(id)) continue;
            String inherited = traceIdFromAncestors(run, runMap, resolved);
            if (inherited != null) resolved.put(id, inherited);
        }

        // Pass 5 — a run with no parent is the root, and in LangSmith a root
        // run's id IS its trace id.
        for (JsonNode run : allRuns) {
            String id = getTextValue(run, "id");
            if (id == null || resolved.containsKey(id)) continue;
            String parentId = getTextValue(run, "parent_run_id");
            if (parentId == null || parentId.isEmpty()) resolved.put(id, id);
        }

        // Pass 6 — last resort. If this request resolved to exactly one trace,
        // stragglers almost certainly belong to it.
        Set<String> distinct = new LinkedHashSet<>(resolved.values());
        for (JsonNode run : allRuns) {
            String id = getTextValue(run, "id");
            if (id == null || resolved.containsKey(id)) continue;
            if (distinct.size() == 1) {
                String only = distinct.iterator().next();
                logger.debug("Run {} has no trace_id and no known ancestor; attaching to the request's only trace {}", id, only);
                resolved.put(id, only);
            } else {
                logger.warn("Run {} could not be attributed to a trace ({} candidates); using its own id", id, distinct.size());
                resolved.put(id, id);
            }
        }

        return resolved;
    }

    /** The run id embedded in the first segment of a LangSmith dotted_order. */
    private String traceIdFromDottedOrder(String dottedOrder) {
        if (dottedOrder == null || dottedOrder.isEmpty()) return null;
        String firstSegment = dottedOrder.split("\\.")[0];
        // Each segment is a timestamp immediately followed by the run's UUID.
        if (firstSegment.length() < 36) return null;
        String candidate = firstSegment.substring(firstSegment.length() - 36);
        return candidate.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
                ? candidate : null;
    }

    /** Walk up parent_run_id looking for a trace id, in this request then the cache. */
    private String traceIdFromAncestors(JsonNode run, Map<String, JsonNode> runMap,
                                        Map<String, String> resolved) {
        Set<String> visited = new HashSet<>();
        String walkId = getTextValue(run, "parent_run_id");

        while (walkId != null && !walkId.isEmpty() && visited.add(walkId)) {
            String known = resolved.get(walkId);
            if (known != null) return known;

            String cached = traceContextCache.getTraceIdForRun(walkId);
            if (cached != null) return cached;

            JsonNode parent = runMap.get(walkId);
            if (parent == null) return null;

            String parentTrace = getTextValue(parent, "trace_id");
            if (parentTrace != null) return parentTrace;

            String grandParent = getTextValue(parent, "parent_run_id");
            if (grandParent == null || grandParent.isEmpty()) return walkId; // parent is the root
            walkId = grandParent;
        }
        return null;
    }

    // ========================================================================
    // PER-TRACE CONTEXT
    // ========================================================================

    /**
     * Build the trace's descriptive context, preferring what this request says
     * and falling back to what the trace's POST said earlier.
     *
     * Every field is "keep the old value unless this request has a better
     * one". A patch-only request knows none of them, and writing a placeholder
     * would overwrite the real name, session and project on a trace that is
     * already correct in Langfuse.
     */
    private TraceContextCache.TraceContext buildTraceContext(String traceId, List<JsonNode> traceRuns) {
        Map<String, String> n8n    = extractN8nContext(traceRuns);
        TraceContextCache.TraceContext cached = traceContextCache.getTrace(traceId);
        TraceContextCache.TraceContext ctx    = new TraceContextCache.TraceContext();

        // Anything a sibling node in the same n8n execution already told us.
        // This is what lets a native AI Agent node — whose trace carries only
        // the execution id — join the session a Code node in the same run set.
        String executionId = n8n.get("execution_id");
        TraceContextCache.ExecutionContext exec = traceContextCache.getExecution(executionId);

        ctx.nodeName = firstNonNull(n8n.get("node_name"),
                       firstNonNull(cached != null ? cached.nodeName : null,
                                    exec != null ? exec.nodeName : null));

        String sessionId = n8n.get("session_id");
        if (sessionId != null && !sessionId.isEmpty()) {
            ctx.sessionId = sessionId;
            ctx.sessionIdIsReliable = true;
        } else if (cached != null && cached.sessionId != null) {
            ctx.sessionId = cached.sessionId;
            ctx.sessionIdIsReliable = cached.sessionIdIsReliable;
        } else if (exec != null && exec.sessionId != null) {
            ctx.sessionId = exec.sessionId;
            ctx.sessionIdIsReliable = true;
        }

        ctx.userId = firstNonNull(n8n.get("user_id"),
                     firstNonNull(cached != null ? cached.userId : null,
                                  exec != null ? exec.userId : null));

        // n8n's workflow/node name is authoritative and may legitimately change
        // the trace name. Anything else is only a guess from whichever runs this
        // request happened to carry, so it must not rename a trace that a
        // previous request already named correctly.
        String authoritative = firstNonNull(n8n.get("workflow_name"), n8n.get("node_name"));
        if (authoritative != null) {
            ctx.name = authoritative;
        } else if (cached != null && cached.name != null) {
            ctx.name = cached.name;
        } else {
            ctx.name = deriveTraceName(traceRuns, n8n);
        }

        ObjectNode metadata = objectMapper.createObjectNode();
        if (cached != null && cached.metadata != null) metadata.setAll(cached.metadata);
        JsonNode fresh = extractTraceMetadata(traceRuns, n8n);
        if (fresh != null && fresh.isObject()) metadata.setAll((ObjectNode) fresh);

        // A native AI Agent node cannot carry the message id, but every trace of
        // the same n8n execution belongs to the same webhook call — so stamp it
        // on, and tag it, to keep all the agents of one message correlatable.
        String msgId = metadata.has("msg_id") ? metadata.get("msg_id").asText()
                     : (exec != null ? exec.msgId : null);
        if (msgId != null && !msgId.isEmpty()) {
            metadata.put("msg_id", msgId);
            ctx.tags.add("msg:" + msgId);
        }
        ctx.metadata = metadata;

        if (cached != null) ctx.tags.addAll(cached.tags);
        ctx.tags.addAll(extractAllTags(traceRuns));

        // Share what this trace knows with the rest of its n8n execution.
        traceContextCache.mergeExecution(executionId, ctx.sessionId, ctx.userId, ctx.nodeName, msgId);

        logger.debug("Trace {} context: node='{}', session='{}', name='{}', tags={}",
                     traceId, ctx.nodeName, ctx.sessionId, ctx.name, ctx.tags);
        return ctx;
    }

    private boolean isPatchOnly(JsonNode run) {
        return run.has(PATCH_ONLY_MARKER) && run.get(PATCH_ONLY_MARKER).asBoolean(false);
    }

    private String firstNonNull(String a, String b) {
        if (a != null && !a.isEmpty()) return a;
        return (b != null && !b.isEmpty()) ? b : null;
    }

    // ========================================================================
    // PER-TRACE EVENT ASSEMBLY
    // ========================================================================

    private void appendTraceEvents(String traceId, List<JsonNode> traceRuns,
                                   TraceContextCache.TraceContext ctx,
                                   Map<String, JsonNode> runMap,
                                   Map<String, String> runTrace,
                                   ArrayNode batch) {

        List<JsonNode> llmRuns  = filterLlmRuns(traceRuns);
        List<JsonNode> spanRuns = filterSpanRuns(traceRuns);

        List<ObjectNode> observations = new ArrayList<>();

        // Remember each run before emitting, so siblings processed later in
        // this same request can recognise it as a valid parent.
        for (JsonNode run : traceRuns) rememberRun(run, traceId);

        JsonNode traceOutput    = null;
        JsonNode traceOutputRun = null;

        for (JsonNode llmRun : llmRuns) {
            generateLlmGenerationEvents(llmRun, traceId, runMap, runTrace, observations);

            boolean hasOutputs = llmRun.has("outputs") && !llmRun.get("outputs").isNull();
            if (hasOutputs || hasError(llmRun)) {
                traceOutput    = hasOutputs ? extractOutput(llmRun) : null;
                traceOutputRun = llmRun;
            }
        }

        for (JsonNode spanRun : spanRuns) {
            generateSpanEvents(spanRun, traceId, runMap, runTrace, observations);

            // A span's output only stands in for the trace output when no LLM
            // produced one — and never from a metadata-only passthrough (whose
            // output is the literal string "metadata-only") nor from a prompt
            // run, whose output is the rendered prompt rather than an answer.
            if (traceOutput == null && !isMetadataOnlyRun(spanRun)
                    && !"prompt".equalsIgnoreCase(getTextValue(spanRun, "run_type"))
                    && spanRun.has("outputs") && !spanRun.get("outputs").isNull()) {
                traceOutput    = spanRun.get("outputs");
                traceOutputRun = spanRun;
            }
        }

        // Phase 1 — trace-create.
        batch.add(createTraceCreateEvent(traceId, ctx, extractTraceInput(traceRuns), getCurrentTimestamp()));

        // Phase 2 — observations.
        for (ObjectNode e : observations) batch.add(e);

        // Phase 3 — trace output, last so nothing in this batch overwrites it.
        if (traceOutput != null || (traceOutputRun != null && hasError(traceOutputRun))) {
            batch.add(createTraceCreateFinalEvent(traceId, traceOutput, getCurrentTimestamp(), traceOutputRun));
            logger.debug("trace-create-final for traceId={} (hasOutput={})", traceId, traceOutput != null);
        }
    }

    /** Record the create-side fields of a run so its later PATCH can reuse them. */
    private void rememberRun(JsonNode run, String traceId) {
        String runId = getTextValue(run, "id");
        if (runId == null) return;

        TraceContextCache.RunInfo info = traceContextCache.getRun(runId);
        if (info == null) info = new TraceContextCache.RunInfo();

        info.traceId = traceId;
        String name = getTextValue(run, "name");
        if (name != null)  info.name = name;
        String runType = getTextValue(run, "run_type");
        if (runType != null) info.runType = runType;
        String startTime = getTextValue(run, "start_time");
        if (startTime != null) info.startTime = startTime;
        String parentRunId = getTextValue(run, "parent_run_id");
        if (parentRunId != null) info.parentRunId = parentRunId;
        String model = extractModel(run);
        if (model != null) info.model = model;

        traceContextCache.putRun(runId, info);
    }

    /**
     * Return the parent to nest this observation under, or null.
     *
     * Langfuse hides an observation whose parentObservationId names something
     * it has never received, so a parent we cannot vouch for is worse than no
     * parent at all: dropping it puts the observation at the trace root, where
     * it is at least visible.
     */
    private String resolveParentObservationId(JsonNode run, String traceId,
                                              Map<String, JsonNode> runMap,
                                              Map<String, String> runTrace) {
        String parentId = getTextValue(run, "parent_run_id");
        if (parentId == null || parentId.isEmpty()) return null;

        if (runMap.containsKey(parentId) && traceId.equals(runTrace.get(parentId))) return parentId;
        if (traceId.equals(traceContextCache.getTraceIdForRun(parentId)))           return parentId;

        logger.debug("Run {}: parent {} is not a known observation on trace {} — attaching at trace root instead",
                     getTextValue(run, "id"), parentId, traceId);
        return null;
    }

    // ========================================================================
    // N8N CONTEXT EXTRACTION
    // ========================================================================

    /**
     * Extract all n8n context fields from ALL runs (including chain runs).
     *
     * FIX (2026-03-30): Process metadata-only runs FIRST so explicit
     * node metadata from RunnablePassthrough (e.g. node:"mfs") always
     * takes priority over n8n auto-injected node names from LLM/chain
     * runs (which carry the n8n node's display name, NOT the project).
     *
     * Additionally, "node:" prefixed tags ALWAYS override any previously
     * found node_name, since they are the most explicit user intent.
     *
     * Node name priority (highest to lowest):
     *   1. "node:<name>" tag (explicit user override — always wins)
     *   2. extra.metadata.node_name / node from metadata-only runs
     *   3. extra.metadata.node_name / node from other runs
     *   4. serialized.kwargs.metadata.node / node_name
     *   5. Plain tag matching a configured project name
     *   6. Inferred from workflow_name or run name pattern
     */
    private Map<String, String> extractN8nContext(List<JsonNode> allRuns) {
        Map<String, String> context = new LinkedHashMap<>();

        // ── REORDER: metadata-only runs first, then the rest ─────────
        // This ensures your RunnablePassthrough's explicit node:"mfs"
        // is found BEFORE n8n auto-injected node from LLM/chain runs.
        List<JsonNode> orderedRuns = new ArrayList<>(allRuns.size());
        List<JsonNode> nonMetadataRuns = new ArrayList<>();
        for (JsonNode run : allRuns) {
            if (isMetadataOnlyRun(run)) {
                orderedRuns.add(run);
            } else {
                nonMetadataRuns.add(run);
            }
        }
        orderedRuns.addAll(nonMetadataRuns);

        logger.debug("extractN8nContext: {} total runs, {} metadata-only (processed first)",
                      allRuns.size(), orderedRuns.size() - nonMetadataRuns.size());

        // ── PASS 1: Check ALL runs for explicit "node:<name>" tags ───
        // This is the strongest signal — if present, it overrides everything.
        String explicitNodeTag = null;
        for (JsonNode run : allRuns) {
            if (run.has("tags") && run.get("tags").isArray()) {
                for (JsonNode tag : run.get("tags")) {
                    if (tag.isTextual()) {
                        String tagLower = tag.asText().trim().toLowerCase();
                        if (tagLower.startsWith("node:")) {
                            explicitNodeTag = tagLower.substring(5).trim();
                            if (!explicitNodeTag.isEmpty()) {
                                logger.info("Found explicit node tag 'node:{}' — this overrides all other sources", explicitNodeTag);
                                break;
                            }
                        }
                    }
                }
                if (explicitNodeTag != null) break;
            }
        }

        // If we found an explicit "node:" tag, set it immediately
        if (explicitNodeTag != null) {
            context.put("node_name", explicitNodeTag);
        }

        // ── PASS 2: Extract all context fields (ordered: metadata-only first) ──
        for (JsonNode run : orderedRuns) {
            // ── PRIMARY: extra.metadata (most reliable) ──────────────────
            if (run.has("extra") && run.get("extra").has("metadata")) {
                JsonNode metadata = run.get("extra").get("metadata");

                // Flat fields
                putIfAbsent(metadata, "session_id",    context);
                putIfAbsent(metadata, "user_id",       context);
                putIfAbsent(metadata, "workflow_id",   context);
                putIfAbsent(metadata, "execution_id",  context);
                putIfAbsent(metadata, "workflow_name", context);
                putIfAbsent(metadata, "node_name",     context);

                // n8n auto-injected "node" field
                if (!context.containsKey("node_name") && metadata.has("node") && !metadata.get("node").isNull()) {
                    String nodeVal = metadata.get("node").asText().trim();
                    if (!nodeVal.isEmpty() && !nodeVal.equals("null")) {
                        // GUARD: Only accept "node" from this run if it matches
                        // a configured project. n8n auto-injects the node's
                        // display name (e.g. "AI Agent1") which is NOT a project.
                        String nodeLower = nodeVal.toLowerCase();
                        if (langfuseConfig.getProjects().containsKey(nodeLower)) {
                            context.put("node_name", nodeLower);
                            logger.debug("Accepted node='{}' (matches configured project)", nodeVal);
                        } else if (isMetadataOnlyRun(run)) {
                            // Metadata-only runs are explicitly set by user code —
                            // trust them even if the value isn't a known project
                            // (could be a new project not yet in config)
                            context.put("node_name", nodeLower);
                            logger.debug("Accepted node='{}' from metadata-only run (user-explicit)", nodeVal);
                        } else {
                            logger.debug("Skipped n8n auto-injected node='{}' (not a configured project: {})",
                                         nodeVal, langfuseConfig.getProjects().keySet());
                        }
                    }
                }

                // Nested workflow object
                if (metadata.has("workflow") && !metadata.get("workflow").isNull()) {
                    JsonNode wf = metadata.get("workflow");
                    if (!context.containsKey("workflow_id")   && wf.has("id"))   context.put("workflow_id",   wf.get("id").asText());
                    if (!context.containsKey("workflow_name") && wf.has("name")) context.put("workflow_name", wf.get("name").asText());
                }

                // user_name (custom field you pass)
                putIfAbsent(metadata, "user_name", context);
            }

            // ── SECONDARY: serialized.kwargs.metadata ────────────────────
            if (run.has("serialized") && run.get("serialized").has("kwargs")) {
                JsonNode kwargs = run.get("serialized").get("kwargs");
                if (kwargs.has("metadata") && !kwargs.get("metadata").isNull()) {
                    JsonNode meta = kwargs.get("metadata");

                    if (!context.containsKey("session_id") && meta.has("session_id") && !meta.get("session_id").isNull()) {
                        String sid = meta.get("session_id").asText();
                        if (!sid.isEmpty() && !sid.equals("null")) {
                            context.put("session_id", sid);
                        }
                    }

                    // node / node_name from serialized metadata
                    if (!context.containsKey("node_name")) {
                        if (meta.has("node") && !meta.get("node").isNull()) {
                            String nodeVal = meta.get("node").asText().trim();
                            if (!nodeVal.isEmpty() && !nodeVal.equals("null")) {
                                // Same guard: only accept if it matches a configured project
                                String nodeLower = nodeVal.toLowerCase();
                                if (langfuseConfig.getProjects().containsKey(nodeLower)) {
                                    context.put("node_name", nodeLower);
                                }
                            }
                        }
                        if (!context.containsKey("node_name") && meta.has("node_name") && !meta.get("node_name").isNull()) {
                            String nodeVal = meta.get("node_name").asText().trim();
                            if (!nodeVal.isEmpty() && !nodeVal.equals("null")) {
                                context.put("node_name", nodeVal.toLowerCase());
                            }
                        }
                    }
                }
            }

            // ── TERTIARY: inputs.session_id ──────────────────────────────
            if (!context.containsKey("session_id") && run.has("inputs")) {
                JsonNode inputs = run.get("inputs");
                if (inputs.has("session_id") && !inputs.get("session_id").isNull()) {
                    String sid = inputs.get("session_id").asText();
                    if (!sid.isEmpty() && !sid.equals("null")) {
                        context.put("session_id", sid);
                    }
                }
            }

            // ── LangSmith "session_name" is the TRACING PROJECT name, set
            // from LANGCHAIN_PROJECT — not a conversation. Using it as the
            // Langfuse sessionId put every trace from an n8n instance into a
            // single session and made the Sessions view useless. Keep it as
            // metadata only; a real session must come from workflow metadata.
            if (!context.containsKey("langsmith_project")) {
                String sessionName = getTextValue(run, "session_name");
                if (sessionName != null && !sessionName.isEmpty() && !sessionName.equals("null")) {
                    context.put("langsmith_project", sessionName);
                }
            }

            // ── TAGS: look for node name in tags array ─────────────────
            // "node:" prefix was already handled in Pass 1 above.
            // Here we only check plain tags that match a configured project.
            if (!context.containsKey("node_name") && run.has("tags") && run.get("tags").isArray()) {
                for (JsonNode tag : run.get("tags")) {
                    if (tag.isTextual()) {
                        String tagLower = tag.asText().trim().toLowerCase();

                        // Plain tag that matches a configured project name
                        if (langfuseConfig.getProjects().containsKey(tagLower)) {
                            context.put("node_name", tagLower);
                            logger.debug("Matched tag '{}' to configured project", tagLower);
                            break;
                        }
                    }
                }
            }
        }

        // ── LAST RESORT: infer node_name from run names or workflow_name ──
        if (!context.containsKey("node_name")) {
            if (context.containsKey("workflow_name")) {
                String inferred = inferNodeFromRunName(context.get("workflow_name"));
                if (inferred != null) {
                    context.put("node_name", inferred);
                    logger.debug("Inferred node_name='{}' from workflow_name '{}'", inferred, context.get("workflow_name"));
                }
            }
        }

        if (!context.containsKey("node_name")) {
            for (JsonNode run : allRuns) {
                String runName = getTextValue(run, "name");
                if (runName != null && !runName.isEmpty()) {
                    String inferred = inferNodeFromRunName(runName);
                    if (inferred != null) {
                        context.put("node_name", inferred);
                        logger.debug("Inferred node_name='{}' from run name '{}'", inferred, runName);
                        break;
                    }
                }
            }
        }

        if (!context.containsKey("node_name")) {
            logger.warn("Could not determine node_name from any source. Data will route to DEFAULT project.");
        }

        return context;
    }

    /**
     * Try to infer the node/project name from a run name.
     *
     * Dynamically uses the project keys configured in application.properties.
     * For example, if you have langfuse.projects.crm and langfuse.projects.texttorule,
     * it checks if the run name starts with "crm_..." or "texttorule_...".
     *
     * Returns null if no pattern matches.
     */
    private String inferNodeFromRunName(String runName) {
        if (runName == null || runName.isEmpty()) return null;
        String lower = runName.toLowerCase().trim();

        // Dynamically check all configured project names
        for (String projectKey : langfuseConfig.getProjects().keySet()) {
            String prefix = projectKey.toLowerCase();
            if (lower.startsWith(prefix + "_") || lower.startsWith(prefix + "-") || lower.equals(prefix)) {
                return projectKey;
            }
        }

        return null;
    }

    private void putIfAbsent(JsonNode node, String field, Map<String, String> map) {
        if (!map.containsKey(field) && node.has(field) && !node.get(field).isNull()) {
            String value = node.get(field).asText();
            if (!value.isEmpty() && !value.equals("null")) {
                map.put(field, value);
            }
        }
    }

    /**
     * Extract and deduplicate tags from ALL runs.
     */
    private List<String> extractAllTags(List<JsonNode> allRuns) {
        Set<String> tags = new LinkedHashSet<>();
        for (JsonNode run : allRuns) {
            if (run.has("tags") && run.get("tags").isArray()) {
                for (JsonNode tag : run.get("tags")) {
                    if (tag.isTextual() && !tag.asText().isEmpty()) {
                        tags.add(tag.asText());
                    }
                }
            }
        }
        return new ArrayList<>(tags);
    }

    // ========================================================================
    // TRACE INPUT/NAME EXTRACTION
    // ========================================================================

    /**
     * Extract the best input to display on the trace.
     *
     * FIXED: Skips metadata-only runs (RunnablePassthrough used solely to
     * inject n8n context) so the trace shows the REAL user query / prompt.
     *
     * Priority:
     *   1. LLM run input (contains the actual prompt with system + user messages)
     *   2. Agent/chain root input that has REAL content (not metadata-only)
     *   3. Any non-metadata-only run with inputs
     *   4. Fallback: even metadata-only input (better than nothing)
     */
    private JsonNode extractTraceInput(List<JsonNode> allRuns) {
        JsonNode llmInput      = null;
        JsonNode agentInput    = null;
        JsonNode anyRealInput  = null;
        JsonNode fallbackInput = null;

        for (JsonNode run : allRuns) {
            if (!run.has("inputs") || run.get("inputs").isNull()) continue;

            String runType  = getTextValue(run, "run_type");
            String parentId = getTextValue(run, "parent_run_id");
            JsonNode inputs = run.get("inputs");

            boolean metadataOnly = isMetadataOnlyRun(run);

            // Priority 1: LLM run (has the full prompt messages)
            if ("llm".equalsIgnoreCase(runType) && llmInput == null) {
                llmInput = extractInput(run);
            }

            // Priority 2: Root agent/chain run with REAL content
            if (!metadataOnly && (parentId == null || parentId.isEmpty()) && agentInput == null) {
                if (inputs.has("input") && !inputs.get("input").isNull()) {
                    agentInput = inputs;
                } else if (inputs.has("messages")) {
                    agentInput = extractInput(run);
                } else {
                    agentInput = inputs;
                }
            }

            // Priority 3: Any non-metadata-only run with inputs
            if (!metadataOnly && anyRealInput == null) {
                anyRealInput = inputs;
            }

            // Fallback: even metadata-only
            if (fallbackInput == null) {
                fallbackInput = inputs;
            }
        }

        if (llmInput     != null) return llmInput;
        if (agentInput   != null) return agentInput;
        if (anyRealInput != null) return anyRealInput;
        return fallbackInput;
    }

    /**
     * Detect if a run is a metadata-only RunnablePassthrough.
     *
     * These runs are injected by the n8n LangChain Code node solely to
     * carry session_id, user_id, node, etc. in metadata. Their input is
     * always {"input":"metadata-only"} and they should NOT be used as the
     * trace's display input/output.
     */
    private boolean isMetadataOnlyRun(JsonNode run) {
        if (run.has("inputs") && !run.get("inputs").isNull()) {
            JsonNode inputs = run.get("inputs");
            // Check for {"input":"metadata-only"}
            if (inputs.has("input") && inputs.get("input").isTextual()) {
                String val = inputs.get("input").asText().toLowerCase().trim();
                if (val.equals("metadata-only") || val.equals("metadata_only")) {
                    return true;
                }
            }
        }
        // Also check outputs for {"input":"metadata-only"} echoed back
        if (run.has("outputs") && !run.get("outputs").isNull()) {
            JsonNode outputs = run.get("outputs");
            if (outputs.has("input") && outputs.get("input").isTextual()) {
                String val = outputs.get("input").asText().toLowerCase().trim();
                if (val.equals("metadata-only") || val.equals("metadata_only")) {
                    return true;
                }
            }
        }
        // Check run name for common passthrough names
        String name = getTextValue(run, "name");
        if (name != null) {
            String lower = name.toLowerCase();
            if (lower.contains("runnablepassthrough") || lower.equals("passthrough")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Derive trace name from all available context.
     *
     * FIXED: Skips metadata-only (RunnablePassthrough) runs when looking
     * for a meaningful name, so traces are named after the real agent/chain.
     *
     * Priority:
     *   1. workflow_name from n8n context
     *   2. node_name from n8n context
     *   3. Name of a root chain/agent run (NOT metadata-only)
     *   4. Name of the first LLM run
     *   5. Any meaningful name from non-metadata runs
     *   6. null — the caller keeps whatever name the trace already has
     */
    private String deriveTraceName(List<JsonNode> allRuns, Map<String, String> n8nContext) {
        // Strategy 1: workflow_name
        if (n8nContext.containsKey("workflow_name")) {
            String wfName = n8nContext.get("workflow_name");
            if (wfName != null && !wfName.isEmpty()) return wfName;
        }

        // Strategy 2: node_name
        if (n8nContext.containsKey("node_name")) {
            String nodeName = n8nContext.get("node_name");
            if (nodeName != null && !nodeName.isEmpty()) return nodeName;
        }

        // Strategy 3: Root run name (skip metadata-only)
        for (JsonNode run : allRuns) {
            if (isMetadataOnlyRun(run)) continue;
            String parentId = getTextValue(run, "parent_run_id");
            if (parentId == null || parentId.isEmpty()) {
                String name = getTextValue(run, "name");
                if (name != null && !name.isEmpty() && !isSessionLikeName(name)) {
                    return name;
                }
            }
        }

        // Strategy 4: First meaningful LLM run name
        for (JsonNode run : allRuns) {
            String runType = getTextValue(run, "run_type");
            if ("llm".equalsIgnoreCase(runType)) {
                String name = getTextValue(run, "name");
                if (name != null && !name.isEmpty() && !isSessionLikeName(name)) {
                    return name;
                }
            }
        }

        // Strategy 5: Any meaningful name from non-metadata runs
        for (JsonNode run : allRuns) {
            if (isMetadataOnlyRun(run)) continue;
            String name = getTextValue(run, "name");
            if (name != null && !name.isEmpty() && !isSessionLikeName(name)) {
                return name;
            }
        }

        return null;
    }

    /**
     * Trace-level metadata: everything the runs recorded plus the n8n
     * workflow context, so the Metadata panel can answer "which workflow,
     * which execution, which user" and not just which model.
     */
    private JsonNode extractTraceMetadata(List<JsonNode> allRuns, Map<String, String> n8nContext) {
        ObjectNode metadata = objectMapper.createObjectNode();

        for (JsonNode run : allRuns) {
            if (!run.has("extra") || !run.get("extra").has("metadata")) continue;
            JsonNode extraMeta = run.get("extra").get("metadata");
            if (!extraMeta.isObject()) continue;
            extraMeta.fields().forEachRemaining(e -> {
                if (metadata.has(e.getKey())) return;             // first run wins
                if (e.getValue() == null || e.getValue().isNull()) return;
                metadata.set(e.getKey(), e.getValue());
            });
        }

        // n8n context last: it is the most specific and should win.
        for (Map.Entry<String, String> e : n8nContext.entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) metadata.put(e.getKey(), e.getValue());
        }

        return metadata;
    }

    // ========================================================================
    // RUN FILTERING
    // ========================================================================

    private List<JsonNode> filterLlmRuns(List<JsonNode> runs) {
        List<JsonNode> llmRuns = new ArrayList<>();
        for (JsonNode run : runs) {
            String runType = getTextValue(run, "run_type");

            if ("llm".equalsIgnoreCase(runType)) {
                llmRuns.add(run);
            } else if (runType == null || runType.isEmpty()) {
                if (isLlmPatchRun(run)) {
                    logger.debug("Including patch-only run {} as LLM run (has LLM outputs)",
                                 getTextValue(run, "id"));
                    llmRuns.add(run);
                }
            }
        }
        return llmRuns;
    }

    private boolean isLlmPatchRun(JsonNode run) {
        if (run.has("outputs") && !run.get("outputs").isNull()) {
            JsonNode outputs = run.get("outputs");
            if (outputs.has("generations")) return true;
            if (outputs.has("llmOutput"))   return true;
        }
        if (hasError(run)) return true;
        return false;
    }

    private List<JsonNode> filterSpanRuns(List<JsonNode> runs) {
        List<JsonNode> spanRuns = new ArrayList<>();
        for (JsonNode run : runs) {
            String runType = getTextValue(run, "run_type");

            if (runType != null && !runType.equalsIgnoreCase("llm")) {
                spanRuns.add(run);
            } else if (runType == null || runType.isEmpty()) {
                if (!isLlmPatchRun(run) && hasSpanOutputs(run)) {
                    logger.debug("Including PATCH-only run {} as span run",
                                 getTextValue(run, "id"));
                    spanRuns.add(run);
                }
            }
        }
        return spanRuns;
    }

    private boolean hasSpanOutputs(JsonNode run) {
        if (run.has("outputs") && !run.get("outputs").isNull()) return true;
        if (run.has("end_time") && !run.get("end_time").isNull()) return true;
        return hasError(run);
    }

    // ========================================================================
    // MERGE AND ERROR HELPERS
    // ========================================================================

    private JsonNode mergeRunWithPatch(JsonNode run, JsonNode patch) {
        if (patch == null) return run;

        ObjectNode merged = run.deepCopy();

        // Always overwrite from patch (final state)
        if (patch.has("outputs") && !patch.get("outputs").isNull())   merged.set("outputs",  patch.get("outputs"));
        if (patch.has("end_time") && !patch.get("end_time").isNull()) merged.set("end_time", patch.get("end_time"));
        if (patch.has("events") && !patch.get("events").isNull())     merged.set("events",   patch.get("events"));
        if (patch.has("error") && !patch.get("error").isNull())       merged.set("error",    patch.get("error"));

        // Copy from patch ONLY if absent in run
        copyIfAbsent(merged, patch, "trace_id");
        copyIfAbsent(merged, patch, "parent_run_id");
        copyIfAbsent(merged, patch, "name");
        copyIfAbsent(merged, patch, "session_name");
        copyIfAbsent(merged, patch, "run_type");
        copyIfAbsent(merged, patch, "extra");
        copyIfAbsent(merged, patch, "inputs");
        copyIfAbsent(merged, patch, "dotted_order");

        return merged;
    }

    private void copyIfAbsent(ObjectNode target, JsonNode source, String field) {
        if ((!target.has(field) || target.get(field).isNull()) && source.has(field) && !source.get(field).isNull()) {
            target.set(field, source.get(field));
        }
    }

    private boolean hasError(JsonNode run) {
        return run != null && run.has("error") && !run.get("error").isNull();
    }

    private String extractErrorMessage(JsonNode run) {
        if (!hasError(run)) return null;

        JsonNode errorNode = run.get("error");

        if (errorNode.isTextual()) {
            String fullError = errorNode.asText();
            String[] lines = fullError.split("\n");

            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && line.contains("Error:")) return line;
            }
            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("at ") && !line.startsWith("http")) return line;
            }
            return fullError.length() > 200 ? fullError.substring(0, 200) : fullError;

        } else if (errorNode.isObject()) {
            if (errorNode.has("message")) return errorNode.get("message").asText();
            if (errorNode.has("error"))   return errorNode.get("error").asText();
            return errorNode.toString();
        }

        return "Unknown error";
    }

    private String extractErrorType(String errorMessage) {
        if (errorMessage == null || errorMessage.isEmpty()) return "Error";

        String[] errorPatterns = {
            "TimeoutError", "APIError", "ValidationError",
            "ConnectionError", "AuthenticationError", "RateLimitError",
            "HTTPError", "NetworkError", "OpenAIError"
        };

        for (String pattern : errorPatterns) {
            if (errorMessage.contains(pattern)) return pattern;
        }

        String[] lines = errorMessage.split("\n");
        for (String line : lines) {
            line = line.trim();
            if (line.contains(":") && !line.startsWith("http")) {
                String type = line.split(":")[0].trim();
                if (type.endsWith("Error") && type.length() < 30 && !type.contains(" ")) return type;
            }
        }

        return "Error";
    }

    // ========================================================================
    // LLM GENERATION EVENT CREATION (PHASE 2 ONLY)
    // ========================================================================

    /**
     * Emit the generation events for one LLM run.
     *
     * The two halves are emitted independently: a create when this request
     * carries the descriptive side, an update when it carries the result side.
     * A patch-only run must not emit a create built from placeholders — a
     * second create with name "LLMGeneration", model "unknown" and startTime
     * "now" overwrites the real values Langfuse already has and destroys the
     * observation's latency.
     */
    private void generateLlmGenerationEvents(JsonNode llmRun, String traceId,
                                             Map<String, JsonNode> runMap,
                                             Map<String, String> runTrace,
                                             List<ObjectNode> observations) {
        String currentTimestamp = getCurrentTimestamp();

        String runId = getTextValue(llmRun, "id");
        String name  = getTextValue(llmRun, "name");
        String model = extractModel(llmRun);

        JsonNode input    = extractInput(llmRun);
        ObjectNode metadata = extractRunMetadata(llmRun);
        ObjectNode params = extractModelParameters(llmRun);

        boolean hasOutputs = llmRun.has("outputs") && !llmRun.get("outputs").isNull();
        boolean hasErr     = hasError(llmRun);
        boolean hasInput   = input != null && !input.isNull();

        String startTime   = getTextValue(llmRun, "start_time");
        String parentObsId = resolveParentObservationId(llmRun, traceId, runMap, runTrace);

        // A create is worth sending whenever this request carries something
        // durable about the observation — but never for a patch-only run,
        // whose "create side" is just what we replayed from the cache and
        // would overwrite the richer original with a thinner copy.
        boolean hasCreateSideData = !isPatchOnly(llmRun)
                && (hasInput || startTime != null || name != null
                    || model != null || (metadata != null && metadata.size() > 0));

        JsonNode output = hasOutputs ? extractOutput(llmRun) : null;
        JsonNode usage  = hasOutputs ? extractUsage(llmRun)  : null;

        logger.debug("LLM run {}: hasInput={}, hasOutputs={}, hasError={}, model={}, parent={}",
                     runId, hasInput, hasOutputs, hasErr, model, parentObsId);

        if (hasCreateSideData) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("id", runId);
            body.put("traceId", traceId);
            if (name != null) body.put("name", name);
            if (startTime != null) body.put("startTime", normalizeTimestamp(startTime));
            if (metadata != null && metadata.size() > 0) body.set("metadata", metadata);
            if (input != null) body.set("input", input);
            if (model != null) body.put("model", model);
            if (parentObsId != null) body.put("parentObservationId", parentObsId);
            // An empty object would blank out parameters a previous event set.
            if (params.size() > 0) body.set("modelParameters", params);

            observations.add(wrapEvent("generation-create", body, currentTimestamp));
        }

        if (hasOutputs || hasErr) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("id", runId);
            body.put("traceId", traceId);
            if (model != null) body.put("model", model);

            // A lone update creates the observation if its create never arrived,
            // so carry the identifying fields — including the real startTime,
            // without which Langfuse computes latency from the wrong instant.
            if (!hasCreateSideData) {
                if (name != null) body.put("name", name);
                if (startTime != null) body.put("startTime", normalizeTimestamp(startTime));
                if (parentObsId != null) body.put("parentObservationId", parentObsId);
                if (input != null) body.set("input", input);
                if (params.size() > 0) body.set("modelParameters", params);
            }

            if (hasErr) {
                String errorMessage = extractErrorMessage(llmRun);
                String errorType    = extractErrorType(errorMessage);
                body.put("level", "ERROR");
                body.put("statusMessage",
                         errorMessage.startsWith(errorType + ":") ? errorMessage
                                                                  : errorType + ": " + errorMessage);
            } else {
                if (output != null) body.set("output", output);
                if (usage != null) {
                    body.set("usage", usage);
                    body.set("usageDetails", buildUsageDetails(usage));
                }
                // Carry the run metadata too: if Langfuse replaces rather than
                // merges observation metadata, a metadata block holding only
                // finish_reason would lose everything the create established.
                ObjectNode updateMeta = objectMapper.createObjectNode();
                if (metadata != null) updateMeta.setAll(metadata);
                ObjectNode outMeta = extractOutputMetadata(llmRun);
                if (outMeta != null) updateMeta.setAll(outMeta);
                if (updateMeta.size() > 0) body.set("metadata", updateMeta);
            }

            String endTime = extractEndTime(llmRun);
            if (endTime != null) body.put("endTime", normalizeTimestamp(endTime));

            observations.add(wrapEvent("generation-update", body, currentTimestamp));
        }
    }

    private ObjectNode wrapEvent(String type, ObjectNode body, String timestamp) {
        ObjectNode event = objectMapper.createObjectNode();
        event.put("id", UUID.randomUUID().toString());
        event.put("type", type);
        event.put("timestamp", timestamp);
        event.set("body", body);
        return event;
    }

    /**
     * Everything the LLM run recorded about itself, for the generation's
     * Metadata panel. The previous whitelist of four ls_* keys dropped n8n's
     * execution context and every custom field the workflow attached.
     */
    private ObjectNode extractRunMetadata(JsonNode run) {
        ObjectNode metadata = objectMapper.createObjectNode();

        if (run.has("extra") && run.get("extra").has("metadata")) {
            JsonNode extraMeta = run.get("extra").get("metadata");
            if (extraMeta.isObject()) {
                extraMeta.fields().forEachRemaining(e -> {
                    if (e.getValue() != null && !e.getValue().isNull()) metadata.set(e.getKey(), e.getValue());
                });
            }
        }

        String runType = getTextValue(run, "run_type");
        if (runType != null) metadata.put("run_type", runType);

        if (run.has("tags") && run.get("tags").isArray() && run.get("tags").size() > 0) {
            metadata.set("tags", run.get("tags"));
        }

        return metadata.size() > 0 ? metadata : null;
    }

    /**
     * Provider-reported details that only exist once the call has returned —
     * finish_reason, the model the provider actually served, fingerprints.
     */
    private ObjectNode extractOutputMetadata(JsonNode run) {
        if (!run.has("outputs") || run.get("outputs").isNull()) return null;

        ObjectNode metadata = objectMapper.createObjectNode();
        JsonNode firstGen = firstGeneration(run.get("outputs"));

        if (firstGen != null) {
            if (firstGen.has("generationInfo") && firstGen.get("generationInfo").isObject()) {
                firstGen.get("generationInfo").fields().forEachRemaining(e -> {
                    if (e.getValue() != null && !e.getValue().isNull()) metadata.set(e.getKey(), e.getValue());
                });
            }
            if (firstGen.has("message") && firstGen.get("message").has("kwargs")) {
                JsonNode kwargs = firstGen.get("message").get("kwargs");
                if (kwargs.has("response_metadata") && kwargs.get("response_metadata").isObject()) {
                    kwargs.get("response_metadata").fields().forEachRemaining(e -> {
                        // Usage is already reported through usage/usageDetails.
                        if (e.getKey().contains("usage") || e.getKey().contains("token")) return;
                        if (e.getValue() != null && !e.getValue().isNull()) metadata.set(e.getKey(), e.getValue());
                    });
                }
            }
        }

        return metadata.size() > 0 ? metadata : null;
    }

    /** The first generation object, unwrapping LangChain's nested array shape. */
    private JsonNode firstGeneration(JsonNode outputs) {
        if (outputs == null || !outputs.has("generations") || !outputs.get("generations").isArray()) return null;
        JsonNode generations = outputs.get("generations");
        if (generations.size() == 0) return null;
        JsonNode group = generations.get(0);
        return (group.isArray() && group.size() > 0) ? group.get(0) : group;
    }

    /**
     * The sampling parameters the call was made with, for the generation's
     * Model Parameters panel. Langfuse only accepts scalars and string arrays
     * here, so anything structured is serialised rather than dropped.
     */
    private ObjectNode extractModelParameters(JsonNode run) {
        ObjectNode params = objectMapper.createObjectNode();

        if (run.has("extra") && run.get("extra").has("invocation_params")) {
            JsonNode ip = run.get("extra").get("invocation_params");
            if (ip.isObject()) {
                ip.fields().forEachRemaining(e -> {
                    String key = e.getKey();
                    // Reported separately as the generation's own "model" field.
                    if (key.equals("model") || key.equals("model_name") || key.equals("_type")) return;
                    JsonNode v = e.getValue();
                    if (v == null || v.isNull()) return;
                    if (v.isValueNode() || isStringArray(v)) params.set(key, v);
                    else params.put(key, v.toString());
                });
            }
        }

        // LangSmith also mirrors the common parameters as ls_* metadata, which
        // is the only place they appear for some integrations.
        if (run.has("extra") && run.get("extra").has("metadata")) {
            JsonNode meta = run.get("extra").get("metadata");
            copyLsParam(meta, "ls_temperature", "temperature", params);
            copyLsParam(meta, "ls_max_tokens",  "max_tokens",  params);
            copyLsParam(meta, "ls_top_p",       "top_p",       params);
            copyLsParam(meta, "ls_stop",        "stop",        params);
        }

        return params;
    }

    private void copyLsParam(JsonNode meta, String from, String to, ObjectNode params) {
        if (params.has(to)) return;
        if (meta.has(from) && !meta.get(from).isNull()) params.set(to, meta.get(from));
    }

    private boolean isStringArray(JsonNode node) {
        if (!node.isArray()) return false;
        for (JsonNode el : node) if (!el.isTextual()) return false;
        return true;
    }

    // ========================================================================
    // SPAN EVENT GENERATION (NON-LLM RUNS)
    // ========================================================================

    /**
     * Emit the span events for one non-LLM run (chain, tool, retriever...).
     *
     * Split the same way as generations: the create carries name/input/start,
     * the update carries output/end/error. A patch-only run emits only the
     * update, so it cannot overwrite the real span with a "Span"-named stub
     * whose startTime is the moment the proxy saw the patch.
     */
    private void generateSpanEvents(JsonNode run, String traceId,
                                    Map<String, JsonNode> runMap,
                                    Map<String, String> runTrace,
                                    List<ObjectNode> observations) {
        String currentTimestamp = getCurrentTimestamp();

        String runId     = getTextValue(run, "id");
        String name      = getTextValue(run, "name");
        String runType   = getTextValue(run, "run_type");
        String startTime = getTextValue(run, "start_time");

        boolean hasInputs  = run.has("inputs")  && !run.get("inputs").isNull();
        boolean hasOutputs = run.has("outputs") && !run.get("outputs").isNull();
        boolean hasEndTime = run.has("end_time") && !run.get("end_time").isNull();
        boolean hasErr     = hasError(run);

        String parentObsId = resolveParentObservationId(run, traceId, runMap, runTrace);

        boolean hasCreateSideData = !isPatchOnly(run)
                && (hasInputs || startTime != null || name != null);

        logger.debug("{} span {}: trace={}, parent={}, hasInputs={}, hasOutputs={}",
                     runType, runId, traceId, parentObsId, hasInputs, hasOutputs);

        if (hasCreateSideData) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("id", runId);
            body.put("traceId", traceId);
            body.put("name", deriveSpanName(name, runType));
            if (startTime != null) body.put("startTime", normalizeTimestamp(startTime));
            if (parentObsId != null) body.put("parentObservationId", parentObsId);
            if (hasInputs) body.set("input", run.get("inputs"));

            ObjectNode spanMeta = extractRunMetadata(run);
            if (spanMeta != null && spanMeta.size() > 0) body.set("metadata", spanMeta);

            observations.add(wrapEvent("span-create", body, currentTimestamp));
        }

        if (hasOutputs || hasEndTime || hasErr) {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("id", runId);
            body.put("traceId", traceId);

            if (!hasCreateSideData) {
                body.put("name", deriveSpanName(name, runType));
                if (startTime != null) body.put("startTime", normalizeTimestamp(startTime));
                if (parentObsId != null) body.put("parentObservationId", parentObsId);
                if (hasInputs) body.set("input", run.get("inputs"));
            }

            if (hasOutputs) body.set("output", run.get("outputs"));

            String endTime = extractEndTime(run);
            if (endTime != null) body.put("endTime", normalizeTimestamp(endTime));

            if (hasErr) {
                String errorMessage = extractErrorMessage(run);
                String errorType    = extractErrorType(errorMessage);
                body.put("level", "ERROR");
                body.put("statusMessage",
                         errorMessage.startsWith(errorType + ":") ? errorMessage
                                                                  : errorType + ": " + errorMessage);
            }

            observations.add(wrapEvent("span-update", body, currentTimestamp));
        }
    }

    private String deriveSpanName(String runName, String runType) {
        if (runName != null && !runName.isEmpty() && !isSessionLikeName(runName)) {
            return runName;
        }
        if (runType != null && !runType.isEmpty()) {
            return runType.substring(0, 1).toUpperCase() + runType.substring(1);
        }
        return "Span";
    }

    /**
     * Detect if a name looks like a session ID.
     * ENHANCED: catches more patterns that shouldn't be used as display names.
     */
    private boolean isSessionLikeName(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase().trim();

        // Explicit session prefixes
        if (lower.startsWith("session-") || lower.startsWith("session_")) return true;

        // Pure UUID pattern
        if (name.matches("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")) return true;

        // Hex strings (hashes, IDs)
        if (name.matches("^[0-9a-fA-F]{16,}$")) return true;

        // Strings that are just numbers
        if (name.matches("^\\d+$")) return true;

        // Common auto-generated patterns
        if (lower.startsWith("run-") || lower.startsWith("run_")) return true;
        if (lower.startsWith("trace-") || lower.startsWith("trace_")) return true;

        return false;
    }

    // ========================================================================
    // LANGFUSE EVENT CREATION HELPERS
    // ========================================================================

    /**
     * The trace's descriptive event.
     *
     * Only fields we actually know are written. Langfuse merges trace events
     * with last-non-null-wins, so emitting a placeholder name or a guessed
     * session id would overwrite the correct values a previous request set.
     */
    private ObjectNode createTraceCreateEvent(String traceId, TraceContextCache.TraceContext ctx,
                                              JsonNode input, String timestamp) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("id", traceId);
        body.put("timestamp", timestamp);

        if (ctx.name != null) body.put("name", ctx.name);
        if (ctx.metadata != null && ctx.metadata.size() > 0) body.set("metadata", ctx.metadata);
        if (ctx.sessionId != null && !ctx.sessionId.isEmpty()) body.put("sessionId", ctx.sessionId);
        if (ctx.userId != null && !ctx.userId.isEmpty()) body.put("userId", ctx.userId);
        if (input != null && !input.isNull()) body.set("input", input);

        if (!ctx.tags.isEmpty()) {
            ArrayNode tagsNode = objectMapper.createArrayNode();
            for (String tag : ctx.tags) tagsNode.add(tag);
            body.set("tags", tagsNode);
        }

        return wrapEvent("trace-create", body, timestamp);
    }

    private ObjectNode createTraceCreateFinalEvent(String traceId, JsonNode output,
                                                    String timestamp, JsonNode run) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("id", traceId);
        body.put("timestamp", timestamp);

        boolean isError = hasError(run);

        if (isError) {
            String errorMessage = extractErrorMessage(run);
            String errorType    = extractErrorType(errorMessage);
            body.put("output",
                     errorMessage.startsWith(errorType + ":")
                         ? errorMessage
                         : errorType + ": " + errorMessage);
        } else {
            if (output != null) body.set("output", output);
        }

        return wrapEvent("trace-create", body, timestamp);
    }

    // ========================================================================
    // EXTRACTION METHODS
    // ========================================================================

    private String extractModel(JsonNode run) {
        if (run.has("extra") && run.get("extra").has("metadata")) {
            JsonNode metadata = run.get("extra").get("metadata");
            if (metadata.has("ls_model_name") && !metadata.get("ls_model_name").asText().isEmpty()) {
                return metadata.get("ls_model_name").asText();
            }
        }
        if (run.has("extra") && run.get("extra").has("invocation_params")) {
            JsonNode params = run.get("extra").get("invocation_params");
            if (params.has("model")      && !params.get("model").asText().isEmpty())      return params.get("model").asText();
            if (params.has("model_name") && !params.get("model_name").asText().isEmpty()) return params.get("model_name").asText();
        }
        if (run.has("serialized") && run.get("serialized").has("kwargs")) {
            JsonNode kwargs = run.get("serialized").get("kwargs");
            if (kwargs.has("model")      && !kwargs.get("model").asText().isEmpty())      return kwargs.get("model").asText();
            if (kwargs.has("model_name") && !kwargs.get("model_name").asText().isEmpty()) return kwargs.get("model_name").asText();
        }
        if (run.has("serialized") && run.get("serialized").has("id")) {
            JsonNode idArr = run.get("serialized").get("id");
            if (idArr.isArray() && idArr.size() > 0) {
                return idArr.get(idArr.size() - 1).asText();
            }
        }
        return null;
    }

    private JsonNode extractInput(JsonNode run) {
        if (!run.has("inputs")) return null;

        JsonNode inputs = run.get("inputs");
        ArrayNode result = objectMapper.createArrayNode();

        if (inputs.has("messages") && inputs.get("messages").isArray()) {
            for (JsonNode messageGroup : inputs.get("messages")) {
                if (messageGroup.isArray()) {
                    for (JsonNode message : messageGroup) {
                        ObjectNode extracted = extractMessageContent(message);
                        if (extracted != null) result.add(extracted);
                    }
                } else {
                    ObjectNode extracted = extractMessageContent(messageGroup);
                    if (extracted != null) result.add(extracted);
                }
            }
        } else if (inputs.has("input") && inputs.get("input").isTextual()) {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("content", inputs.get("input").asText());
            result.add(msg);
        } else if (inputs.has("prompts") && inputs.get("prompts").isArray()) {
            // Completion-style LangChain runs carry plain prompt strings.
            for (JsonNode prompt : inputs.get("prompts")) {
                if (!prompt.isTextual()) continue;
                ObjectNode msg = objectMapper.createObjectNode();
                msg.put("role", "user");
                msg.put("content", prompt.asText());
                result.add(msg);
            }
        } else if (inputs.isTextual()) {
            ObjectNode msg = objectMapper.createObjectNode();
            msg.put("content", inputs.asText());
            result.add(msg);
        }

        if (result.size() > 0) return result;

        // Nothing matched a known chat shape. Showing the raw inputs beats
        // showing an empty Input panel, which is what returning null caused.
        return inputs.isNull() ? null : inputs;
    }

    private ObjectNode extractMessageContent(JsonNode message) {
        ObjectNode result = objectMapper.createObjectNode();

        // Format 1: LangChain constructor {id:[...], kwargs:{content:...}}
        if (message.has("kwargs") && message.get("kwargs").has("content")) {
            String content = extractContentValue(message.get("kwargs").get("content"));
            if (content == null) return null;
            result.put("content", content);

            if (message.has("id") && message.get("id").isArray()) {
                JsonNode idArr = message.get("id");
                if (idArr.size() > 0) {
                    String lastId = idArr.get(idArr.size() - 1).asText().toLowerCase();
                    if      (lastId.contains("human"))                              result.put("role", "user");
                    else if (lastId.equals("aimessage") || lastId.equals("ai"))     result.put("role", "assistant");
                    else if (lastId.contains("chat") && lastId.contains("message")) result.put("role", "assistant");
                    else if (lastId.contains("system"))                             result.put("role", "system");
                    else if (lastId.contains("tool") || lastId.contains("function"))result.put("role", "tool");
                    else if (lastId.endsWith("aimessage") || lastId.startsWith("ai")) result.put("role", "assistant");
                }
            }

            if (!result.has("role") && message.get("kwargs").has("type")) {
                String type = message.get("kwargs").get("type").asText().toLowerCase();
                if      (type.equals("human"))  result.put("role", "user");
                else if (type.equals("ai"))     result.put("role", "assistant");
                else if (type.equals("system")) result.put("role", "system");
                else if (type.equals("tool"))   result.put("role", "tool");
            }

            return result;
        }

        // Format 2: Simple {role, content}
        if (message.has("content")) {
            String content = extractContentValue(message.get("content"));
            if (content == null) return null;
            result.put("content", content);
            if (message.has("role")) result.put("role", message.get("role").asText());
            return result;
        }

        return null;
    }

    private String extractContentValue(JsonNode contentNode) {
        if (contentNode == null || contentNode.isNull()) return null;
        if (contentNode.isTextual()) return contentNode.asText();

        if (contentNode.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : contentNode) {
                if (block.has("text") && block.get("text").isTextual()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(block.get("text").asText());
                } else if (block.has("content") && block.get("content").isTextual()) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(block.get("content").asText());
                }
            }
            return sb.length() > 0 ? sb.toString() : null;
        }

        return null;
    }

    // ========================================================================
    // OUTPUT EXTRACTION
    // ========================================================================

    private JsonNode extractOutput(JsonNode run) {
        if (!run.has("outputs") || run.get("outputs").isNull()) return null;

        JsonNode outputs = run.get("outputs");
        ObjectNode result = objectMapper.createObjectNode();

        // GENERATIONS-BASED FORMATS
        if (outputs.has("generations") && outputs.get("generations").isArray()) {
            JsonNode generations = outputs.get("generations");
            if (generations.size() > 0) {
                JsonNode firstGenGroup = generations.get(0);
                JsonNode firstGen = firstGenGroup.isArray() && firstGenGroup.size() > 0
                    ? firstGenGroup.get(0) : firstGenGroup;

                // Format A — direct "text"
                if (firstGen.has("text") && firstGen.get("text").isTextual()) {
                    String text = firstGen.get("text").asText();
                    if (!text.isEmpty()) {
                        result.put("content", text);
                        result.put("role", "assistant");
                        return result;
                    }
                }

                // Format B/C/D/E — message-based
                if (firstGen.has("message")) {
                    JsonNode message = firstGen.get("message");

                    if (message.has("kwargs")) {
                        JsonNode kwargs = message.get("kwargs");

                        // B — kwargs.content
                        if (kwargs.has("content")) {
                            String extracted = extractContentValue(kwargs.get("content"));
                            if (extracted != null && !extracted.isEmpty()) {
                                result.put("content", extracted);
                                result.put("role", "assistant");
                                return result;
                            }
                        }

                        // D — Tool calls
                        if (kwargs.has("tool_calls") && kwargs.get("tool_calls").isArray()
                                && kwargs.get("tool_calls").size() > 0) {
                            ArrayNode toolCalls = (ArrayNode) kwargs.get("tool_calls");
                            result.set("tool_calls", toolCalls);
                            result.put("role", "assistant");
                            StringBuilder sb = new StringBuilder();
                            for (JsonNode tc : toolCalls) {
                                if (tc.has("args")) {
                                    if (sb.length() > 0) sb.append("\n");
                                    JsonNode args = tc.get("args");
                                    sb.append(args.isTextual() ? args.asText() : args.toString());
                                }
                            }
                            if (sb.length() > 0) result.put("content", sb.toString());
                            return result;
                        }

                        // E — Legacy function_call
                        if (kwargs.has("additional_kwargs")) {
                            JsonNode addKwargs = kwargs.get("additional_kwargs");
                            if (addKwargs.has("function_call")) {
                                JsonNode fc = addKwargs.get("function_call");
                                result.set("function_call", fc);
                                result.put("role", "assistant");
                                if (fc.has("arguments")) result.put("content", fc.get("arguments").asText());
                                return result;
                            }
                            if (addKwargs.has("tool_calls") && addKwargs.get("tool_calls").isArray()
                                    && addKwargs.get("tool_calls").size() > 0) {
                                result.set("tool_calls", addKwargs.get("tool_calls"));
                                result.put("role", "assistant");
                                return result;
                            }
                        }
                    }

                    // C — message.content directly
                    if (message.has("content")) {
                        String extracted = extractContentValue(message.get("content"));
                        if (extracted != null && !extracted.isEmpty()) {
                            result.put("content", extracted);
                            result.put("role", "assistant");
                            return result;
                        }
                    }
                }

                // Direct content at firstGen level
                if (firstGen.has("content")) {
                    String extracted = extractContentValue(firstGen.get("content"));
                    if (extracted != null && !extracted.isEmpty()) {
                        result.put("content", extracted);
                        result.put("role", "assistant");
                        return result;
                    }
                }

                // Nothing readable as text. A tool-calling turn looks like this:
                // empty text plus generationInfo.finish_reason "tool_calls".
                // Returning the generation object keeps its structure in the UI,
                // where stringifying it into "content" showed raw JSON instead.
                logger.debug("Generation has no text content for run {} — passing it through structurally",
                             getTextValue(run, "id"));
                return firstGen;
            }
        }

        // Format F — simple text output
        if (outputs.has("output")) {
            JsonNode outNode = outputs.get("output");
            result.put("content", outNode.isTextual() ? outNode.asText() : outNode.toString());
            result.put("role", "assistant");
            return result;
        }

        // Format G — agent returnValues
        if (outputs.has("returnValues") && outputs.get("returnValues").has("output")) {
            result.put("content", outputs.get("returnValues").get("output").asText());
            result.put("role", "assistant");
            return result;
        }

        // Absolute fallback — return entire outputs
        logger.warn("No recognized output format — returning raw outputs for run: {}",
                     getTextValue(run, "id"));
        return outputs;
    }

    // ========================================================================
    // USAGE EXTRACTION
    // ========================================================================

    private JsonNode extractUsage(JsonNode run) {
        if (!run.has("outputs") || run.get("outputs").isNull()) return null;

        JsonNode outputs = run.get("outputs");
        int inputTokens  = 0;
        int outputTokens = 0;
        int totalTokens  = -1;
        boolean found = false;

        // Source 1-3: llmOutput block
        if (outputs.has("llmOutput") && !outputs.get("llmOutput").isNull()) {
            JsonNode llmOutput = outputs.get("llmOutput");

            if (llmOutput.has("tokenUsage") && !llmOutput.get("tokenUsage").isNull()) {
                JsonNode tu = llmOutput.get("tokenUsage");
                int inp = getIntFrom(tu, "promptTokens", "prompt_tokens", "input_tokens", 0);
                int out = getIntFrom(tu, "completionTokens", "completion_tokens", "output_tokens", 0);
                int tot = getIntFrom(tu, "totalTokens", "total_tokens", null, -1);
                if (inp > 0 || out > 0) { inputTokens = Math.max(inputTokens, inp); outputTokens = Math.max(outputTokens, out); if (tot > 0) totalTokens = tot; found = true; }
            }

            if (llmOutput.has("usage") && !llmOutput.get("usage").isNull()) {
                JsonNode u = llmOutput.get("usage");
                int inp = getIntFrom(u, "input_tokens", "promptTokens", "prompt_tokens", 0);
                int out = getIntFrom(u, "output_tokens", "completionTokens", "completion_tokens", 0);
                int tot = getIntFrom(u, "total_tokens", "totalTokens", null, -1);
                if (inp > 0 || out > 0) { inputTokens = Math.max(inputTokens, inp); outputTokens = Math.max(outputTokens, out); if (tot > 0) totalTokens = tot; found = true; }
            }

            if (llmOutput.has("usage_metadata") && !llmOutput.get("usage_metadata").isNull()) {
                JsonNode u = llmOutput.get("usage_metadata");
                int inp = getIntFrom(u, "prompt_token_count", "input_tokens", "prompt_tokens", 0);
                int out = getIntFrom(u, "candidates_token_count", "output_tokens", "completion_tokens", 0);
                int tot = getIntFrom(u, "total_token_count", "total_tokens", null, -1);
                if (inp > 0 || out > 0) { inputTokens = Math.max(inputTokens, inp); outputTokens = Math.max(outputTokens, out); if (tot > 0) totalTokens = tot; found = true; }
            }
        }

        // Source 4-6: generation-level usage
        if (outputs.has("generations") && outputs.get("generations").isArray()) {
            JsonNode generations = outputs.get("generations");
            if (generations.size() > 0) {
                JsonNode firstGroup = generations.get(0);
                JsonNode firstGen = firstGroup.isArray() && firstGroup.size() > 0 ? firstGroup.get(0) : firstGroup;

                if (firstGen.has("message") && firstGen.get("message").has("kwargs")) {
                    JsonNode kwargs = firstGen.get("message").get("kwargs");

                    if (kwargs.has("usage_metadata") && !kwargs.get("usage_metadata").isNull()) {
                        JsonNode u = kwargs.get("usage_metadata");
                        int inp = getIntFrom(u, "input_tokens", "prompt_tokens", null, 0);
                        int out = getIntFrom(u, "output_tokens", "completion_tokens", null, 0);
                        int tot = getIntFrom(u, "total_tokens", null, null, -1);
                        if (inp > 0 || out > 0) { inputTokens = Math.max(inputTokens, inp); outputTokens = Math.max(outputTokens, out); if (tot > 0) totalTokens = tot; found = true; }
                    }

                    if (kwargs.has("response_metadata") && !kwargs.get("response_metadata").isNull()) {
                        JsonNode rm = kwargs.get("response_metadata");
                        JsonNode usageSource = null;
                        if (rm.has("usage") && !rm.get("usage").isNull()) usageSource = rm.get("usage");
                        if (usageSource == null && rm.has("token_usage") && !rm.get("token_usage").isNull()) usageSource = rm.get("token_usage");

                        if (usageSource != null) {
                            int inp = getIntFrom(usageSource, "prompt_tokens", "input_tokens", null, 0);
                            int out = getIntFrom(usageSource, "completion_tokens", "output_tokens", null, 0);
                            int tot = getIntFrom(usageSource, "total_tokens", null, null, -1);
                            if (inp > 0 || out > 0) { inputTokens = Math.max(inputTokens, inp); outputTokens = Math.max(outputTokens, out); if (tot > 0) totalTokens = tot; found = true; }
                        }
                    }
                }
            }
        }

        if (!found || (inputTokens == 0 && outputTokens == 0)) return null;
        return buildUsageNode(inputTokens, outputTokens, totalTokens);
    }

    private JsonNode buildUsageNode(int input, int output, int total) {
        if (input == 0 && output == 0) return null;
        int finalTotal = (total <= 0) ? input + output : total;
        ObjectNode usage = objectMapper.createObjectNode();
        usage.put("input",  input);
        usage.put("output", output);
        usage.put("total",  finalTotal);
        usage.put("unit",   "TOKENS");
        return usage;
    }

    /**
     * The numeric-only twin of the usage object. Langfuse validates
     * usageDetails as a map of string to number, so the "unit" string that
     * belongs in usage would make the whole event fail schema validation and
     * be dropped from the batch.
     */
    private ObjectNode buildUsageDetails(JsonNode usage) {
        ObjectNode details = objectMapper.createObjectNode();
        for (String field : new String[]{"input", "output", "total"}) {
            if (usage.has(field) && usage.get(field).isNumber()) details.put(field, usage.get(field).asInt());
        }
        return details;
    }

    private int getIntFrom(JsonNode node, String f1, String f2, String f3, int defaultVal) {
        if (f1 != null && node.has(f1) && !node.get(f1).isNull()) return node.get(f1).asInt();
        if (f2 != null && node.has(f2) && !node.get(f2).isNull()) return node.get(f2).asInt();
        if (f3 != null && node.has(f3) && !node.get(f3).isNull()) return node.get(f3).asInt();
        return defaultVal;
    }

    // ========================================================================
    // UTILITY METHODS
    // ========================================================================

    private Map<String, JsonNode> buildRunMap(List<JsonNode> runs) {
        Map<String, JsonNode> runMap = new HashMap<>();
        for (JsonNode run : runs) {
            String runId = getTextValue(run, "id");
            if (runId != null) runMap.put(runId, run);
        }
        return runMap;
    }

    private String extractEndTime(JsonNode run) {
        if (run.has("end_time")) {
            JsonNode endTime = run.get("end_time");
            if (endTime.isTextual()) return endTime.asText();
            if (endTime.isNumber()) {
                long ts = endTime.asLong();
                return (ts > 1_000_000_000_000L)
                    ? Instant.ofEpochMilli(ts).toString()
                    : Instant.ofEpochSecond(ts).toString();
            }
        }
        return null;
    }

    private String getCurrentTimestamp() {
        return Instant.now().toString();
    }

    private String normalizeTimestamp(String timestamp) {
        if (timestamp == null) return getCurrentTimestamp();
        try {
            long ts = Long.parseLong(timestamp);
            return (ts > 1_000_000_000_000L)
                ? Instant.ofEpochMilli(ts).toString()
                : Instant.ofEpochSecond(ts).toString();
        } catch (NumberFormatException e) {
            return timestamp;
        }
    }

    private String getTextValue(JsonNode node, String field) {
        if (node != null && node.has(field) && !node.get(field).isNull()) {
            return node.get(field).asText();
        }
        return null;
    }
}