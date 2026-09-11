package com.proxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Part;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rebuilds the {"post":[...],"patch":[...]} document from a LangSmith
 * multipart ingestion request.
 *
 * Current LangSmith SDKs (langsmith-js 0.6+, which n8n bundles) POST to
 * /runs/multipart rather than sending one JSON body, and they do it whatever
 * the /info handshake advertises. Each run is spread across several form
 * parts — the run itself under "post.&lt;id&gt;", and its large fields under
 * sibling parts named "post.&lt;id&gt;.inputs", ".outputs", ".events",
 * ".extra", ".serialized":
 *
 *   name="post.01a0-...-8b24901ffc8e"          {"id":"...","run_type":"chain",...}
 *   name="post.01a0-...-8b24901ffc8e.inputs"   {"question":"..."}
 *   name="post.01a0-...-8b24901ffc8e.outputs"  {"lc":1,...}
 *
 * Reassembling them into the batch shape means the rest of the pipeline needs
 * no knowledge of how the request arrived.
 */
@Service
public class MultipartRunAssembler {

    private static final Logger logger = LoggerFactory.getLogger(MultipartRunAssembler.class);

    private final ObjectMapper objectMapper;

    @Autowired
    public MultipartRunAssembler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public JsonNode assemble(Collection<Part> parts) throws Exception {
        // Insertion-ordered so runs keep the order the SDK sent them in.
        Map<String, ObjectNode> posts   = new LinkedHashMap<>();
        Map<String, ObjectNode> patches = new LinkedHashMap<>();

        for (Part part : parts) {
            String name = part.getName();
            if (name == null || name.isEmpty()) continue;

            int firstDot = name.indexOf('.');
            if (firstDot < 0) {
                logger.debug("Ignoring multipart part with no run id: {}", name);
                continue;
            }

            String kind = name.substring(0, firstDot);
            String rest = name.substring(firstDot + 1);

            Map<String, ObjectNode> target;
            if ("post".equals(kind))       target = posts;
            else if ("patch".equals(kind)) target = patches;
            else {
                // "attachment.*" is binary user content and "feedback.*" is
                // scores, neither of which this proxy forwards.
                logger.debug("Ignoring multipart part of kind '{}'", kind);
                continue;
            }

            int secondDot = rest.indexOf('.');
            String runId = secondDot < 0 ? rest : rest.substring(0, secondDot);
            String field = secondDot < 0 ? null : rest.substring(secondDot + 1);

            byte[] raw = part.getInputStream().readAllBytes();
            if (raw.length == 0) continue;

            JsonNode value;
            try {
                value = objectMapper.readTree(new String(raw, StandardCharsets.UTF_8));
            } catch (Exception e) {
                logger.warn("Multipart part '{}' is not JSON, skipping: {}", name, e.getMessage());
                continue;
            }

            ObjectNode run = target.computeIfAbsent(runId, k -> objectMapper.createObjectNode());

            if (field == null) {
                // The run body itself. Merge rather than replace: a sibling
                // field part may already have been read.
                if (value.isObject()) {
                    value.fields().forEachRemaining(e -> run.set(e.getKey(), e.getValue()));
                } else {
                    logger.warn("Multipart run body '{}' is not an object, skipping", name);
                }
            } else {
                run.set(field, value);
            }

            if (!run.has("id")) run.put("id", runId);
        }

        ArrayNode postArray  = objectMapper.createArrayNode();
        ArrayNode patchArray = objectMapper.createArrayNode();
        posts.values().forEach(postArray::add);
        patches.values().forEach(patchArray::add);

        ObjectNode assembled = objectMapper.createObjectNode();
        assembled.set("post", postArray);
        assembled.set("patch", patchArray);

        logger.info("Assembled multipart request: {} part(s) → {} post run(s), {} patch run(s)",
                    parts.size(), postArray.size(), patchArray.size());

        return assembled;
    }
}
