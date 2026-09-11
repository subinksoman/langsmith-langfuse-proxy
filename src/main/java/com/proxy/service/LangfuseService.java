package com.proxy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.proxy.config.LangfuseConfig;
import com.proxy.config.ProxyConfig;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Service
public class LangfuseService {

    private static final Logger logger = LoggerFactory.getLogger(LangfuseService.class);

    private final CloseableHttpClient httpClient;
    private final LangfuseConfig langfuseConfig;
    private final ProxyConfig proxyConfig;
    private final ObjectMapper objectMapper;

    @Autowired
    public LangfuseService(CloseableHttpClient httpClient,
                           LangfuseConfig langfuseConfig,
                           ProxyConfig proxyConfig,
                           ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.langfuseConfig = langfuseConfig;
        this.proxyConfig = proxyConfig;
        this.objectMapper = objectMapper;
    }

    public boolean sendToLangfuse(JsonNode langfuseData) {
        return sendToLangfuse(langfuseData, null);
    }

    public boolean sendToLangfuse(JsonNode langfuseData, String nodeName) {
        try {
            LangfuseConfig.ProjectConfig project = langfuseConfig.getProjectForNode(nodeName);

            if (project.getPublicKey() == null || project.getPublicKey().isBlank()
                    || project.getSecretKey() == null || project.getSecretKey().isBlank()) {
                logger.error("Missing Langfuse credentials for node='{}'. Check application.properties.",
                        nodeName != null ? nodeName : "(default)");
                return false;
            }

            String url = langfuseConfig.getIngestionUrl(project);
            logger.info("Sending data to Langfuse: url={}, node='{}', project_key_prefix='{}'",
                    url,
                    nodeName != null ? nodeName : "(default)",
                    project.getPublicKey().substring(0, Math.min(12, project.getPublicKey().length())) + "...");

            HttpPost httpPost = new HttpPost(url);
            httpPost.setHeader("Content-Type",  "application/json");
            httpPost.setHeader("Accept",        "application/json");

            String auth        = project.getPublicKey() + ":" + project.getSecretKey();
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
            httpPost.setHeader("Authorization", "Basic " + encodedAuth);

            String jsonBody = objectMapper.writeValueAsString(langfuseData);
            httpPost.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

            if (proxyConfig.isLogRequests()) {
                logger.debug("Request to Langfuse (node='{}'): {}", nodeName, jsonBody);
            }

            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int    statusCode   = response.getCode();
                String responseBody = "";

                if (response.getEntity() != null) {
                    responseBody = new String(
                            response.getEntity().getContent().readAllBytes(),
                            StandardCharsets.UTF_8);
                }

                if (proxyConfig.isLogResponses()) {
                    logger.debug("Response from Langfuse: status={}, body={}", statusCode, responseBody);
                }

                if (statusCode >= 200 && statusCode < 300) {
                    int rejected = logPartialFailures(responseBody, nodeName);
                    if (rejected > 0) {
                        logger.error("Langfuse accepted the request (status {}) but REJECTED {} event(s) for node='{}'. "
                                   + "Those events will not appear in the UI.",
                                statusCode, rejected, nodeName != null ? nodeName : "(default)");
                        return false;
                    }
                    logger.info("Successfully sent to Langfuse project for node='{}' (status: {})",
                            nodeName != null ? nodeName : "(default)", statusCode);
                    return true;
                } else {
                    logger.error("Langfuse returned error status: {}, body: {}", statusCode, responseBody);
                    return false;
                }
            }

        } catch (Exception e) {
            logger.error("Error sending to Langfuse (node='{}'): {}", nodeName, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Report the events Langfuse threw away.
     *
     * Langfuse ingestion answers 207 Multi-Status: the HTTP call succeeds even
     * when individual events fail schema validation, and the failures are only
     * described in the body. Treating any 2xx as success made every such
     * rejection invisible, which is exactly the case where data is missing
     * from the UI and the proxy logs say everything went fine.
     *
     * @return the number of rejected events (0 when all were accepted)
     */
    private int logPartialFailures(String responseBody, String nodeName) {
        if (responseBody == null || responseBody.isBlank()) return 0;
        try {
            JsonNode body = objectMapper.readTree(responseBody);
            JsonNode errors = body.get("errors");
            if (errors == null || !errors.isArray() || errors.isEmpty()) return 0;

            for (JsonNode error : errors) {
                logger.error("Langfuse REJECTED event id={} status={} message={} error={}",
                        text(error, "id"), text(error, "status"),
                        text(error, "message"), text(error, "error"));
            }
            return errors.size();

        } catch (Exception e) {
            logger.warn("Could not parse Langfuse ingestion response for node='{}': {}", nodeName, e.getMessage());
            return 0;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.isValueNode() ? v.asText() : v.toString();
    }

    public boolean testConnection() {
        try {
            String url = langfuseConfig.getBaseUrl() + "/api/public/health";
            logger.info("Testing connection to Langfuse: {}", url);

            HttpGet httpGet = new HttpGet(url);

            try (CloseableHttpResponse response = httpClient.execute(httpGet)) {
                int statusCode = response.getCode();
                return statusCode >= 200 && statusCode < 300;
            }

        } catch (Exception e) {
            logger.error("Error testing Langfuse connection: {}", e.getMessage());
            return false;
        }
    }
}
