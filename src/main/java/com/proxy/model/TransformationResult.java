package com.proxy.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Wraps the result of TransformationService.transform().
 * Carries both the Langfuse-ready payload AND the node name extracted
 * from n8n metadata so ProxyController can route to the correct
 * Langfuse project.
 */
public class TransformationResult {

    private final JsonNode payload;
    private final String   nodeName;

    public TransformationResult(JsonNode payload, String nodeName) {
        this.payload  = payload;
        this.nodeName = nodeName;
    }

    /** The fully-built Langfuse ingestion payload ready to POST. */
    public JsonNode getPayload() {
        return payload;
    }

    /**
     * The node name from n8n metadata (e.g. "crm", "texttorule").
     * May be null if no node was set in the workflow.
     */
    public String getNodeName() {
        return nodeName;
    }
}
