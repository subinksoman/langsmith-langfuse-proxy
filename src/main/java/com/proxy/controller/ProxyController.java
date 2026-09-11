package com.proxy.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proxy.config.ProxyConfig;
import com.proxy.model.TransformationResult;
import com.proxy.service.TransformationService;
import com.proxy.service.LangfuseService;
import com.proxy.service.MultipartRunAssembler;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class ProxyController {

    private static final Logger logger = LoggerFactory.getLogger(ProxyController.class);

    private final TransformationService transformationService;
    private final LangfuseService langfuseService;
    private final ProxyConfig proxyConfig;
    private final ObjectMapper objectMapper;
    private final MultipartRunAssembler multipartRunAssembler;

    @Autowired
    public ProxyController(TransformationService transformationService,
                          LangfuseService langfuseService,
                          ProxyConfig proxyConfig,
                          ObjectMapper objectMapper,
                          MultipartRunAssembler multipartRunAssembler) {
        this.transformationService = transformationService;
        this.langfuseService = langfuseService;
        this.proxyConfig = proxyConfig;
        this.objectMapper = objectMapper;
        this.multipartRunAssembler = multipartRunAssembler;
    }

    @PostMapping(value = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> handleRuns(@RequestBody String requestBody) {
        try {
            if (proxyConfig.isLogRequests()) {
                logger.info("Received request at /runs");
                logger.debug("Request body: {}", requestBody);
            }

            JsonNode langsmithData = objectMapper.readTree(requestBody);
            return forward(langsmithData);

        } catch (Exception e) {
            logger.error("Error processing request: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "error",
                "message", String.valueOf(e.getMessage())
            ));
        }
    }

    /**
     * Ingestion for current LangSmith SDKs.
     *
     * langsmith-js 0.6+ (the version n8n bundles) posts runs as multipart form
     * data regardless of what /info advertises, so without this endpoint every
     * trace n8n produces is rejected and lost.
     */
    @PostMapping(value = "/runs/multipart", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> handleMultipart(HttpServletRequest request) {
        try {
            if (proxyConfig.isLogRequests()) {
                logger.info("Received multipart request at /runs/multipart");
            }
            JsonNode langsmithData = multipartRunAssembler.assemble(request.getParts());
            return forward(langsmithData);

        } catch (Exception e) {
            logger.error("Error processing multipart request: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "error",
                "message", String.valueOf(e.getMessage())
            ));
        }
    }

    /** Transform a LangSmith batch and deliver each project's events. */
    private ResponseEntity<Map<String, Object>> forward(JsonNode langsmithData) {
        try {
            // One request can carry traces for several n8n nodes, and each node
            // routes to a different Langfuse project with its own credentials,
            // so each gets its own batch.
            List<TransformationResult> results = transformationService.transformAll(langsmithData);

            if (results.isEmpty()) {
                logger.info("No processable runs found in request, skipping");
                return ResponseEntity.ok(Map.of(
                    "status", "skipped",
                    "message", "No processable runs found in request"
                ));
            }

            List<String> failed = new ArrayList<>();
            int delivered = 0;

            for (TransformationResult result : results) {
                String nodeName = result.getNodeName();
                logger.info("Routing batch of {} event(s) to Langfuse project for node='{}'",
                        result.getPayload().get("batch").size(),
                        nodeName != null ? nodeName : "(default)");

                if (proxyConfig.isLogResponses()) {
                    logger.debug("Transformed data (node='{}'): {}", nodeName, result.getPayload().toString());
                }

                if (langfuseService.sendToLangfuse(result.getPayload(), nodeName)) {
                    delivered++;
                } else {
                    failed.add(nodeName != null ? nodeName : "(default)");
                }
            }

            if (failed.isEmpty()) {
                logger.info("Successfully forwarded {} batch(es) to Langfuse", delivered);
                return ResponseEntity.ok(Map.of(
                    "status", "success",
                    "message", "Data forwarded to Langfuse",
                    "batches", delivered
                ));
            }

            logger.error("Failed to forward {} of {} batch(es) to Langfuse (nodes: {})",
                    failed.size(), results.size(), failed);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                "status", "error",
                "message", "Failed to forward to Langfuse",
                "delivered", delivered,
                "failedNodes", failed
            ));

        } catch (Exception e) {
            logger.error("Error processing request: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "status", "error",
                "message", String.valueOf(e.getMessage())
            ));
        }
    }

    @PostMapping(value = "/runs/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> handleBatchRuns(@RequestBody String requestBody) {
        return handleRuns(requestBody);
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "healthy",
            "service", "langsmith-langfuse-proxy"
        ));
    }

    @GetMapping("/info")
    public ResponseEntity<Map<String, String>> info() {
        return ResponseEntity.ok(Map.of(
            "service", "LangSmith to Langfuse Proxy",
            "version", "2.0.1",
            "description", "Converts LangSmith tracing data to Langfuse format"
        ));
    }

    @GetMapping(value = {"/runs/info", "/runs/version", "/runs/ok",
                         "/version",   "/ok"})
    public ResponseEntity<Map<String, Object>> handleGetInfo() {
        return ResponseEntity.ok(Map.of(
            "version", "2.0.1",
            "batch_ingest_config", Map.of(
                "use_multipart_endpoint", true,
                "size_limit_bytes",       20971520,
                "size_limit",             100
            )
        ));
    }

    @RequestMapping(value = "/**", method = {RequestMethod.POST, RequestMethod.PUT, RequestMethod.PATCH})
    public ResponseEntity<Map<String, Object>> catchAll(@RequestBody(required = false) String requestBody,
                                                         @RequestHeader Map<String, String> headers) {
        logger.info("Catch-all endpoint hit");
        if (requestBody != null && !requestBody.isEmpty()) {
            return handleRuns(requestBody);
        }
        return ResponseEntity.ok(Map.of(
            "status", "received",
            "message", "Request received but no body to process"
        ));
    }
}
