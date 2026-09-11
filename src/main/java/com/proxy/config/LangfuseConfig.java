package com.proxy.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;

@Configuration
@ConfigurationProperties(prefix = "langfuse")
public class LangfuseConfig {

    private static final Logger logger = LoggerFactory.getLogger(LangfuseConfig.class);

    private String baseUrl;
    private String ingestionEndpoint;
    private String publicKey;
    private String secretKey;
    private Map<String, ProjectConfig> projects = new HashMap<>();

    public static class ProjectConfig {
        private String publicKey;
        private String secretKey;
        private String baseUrl;
        private String ingestionEndpoint;

        public String getPublicKey()                        { return publicKey; }
        public void   setPublicKey(String k)                { this.publicKey = k; }
        public String getSecretKey()                        { return secretKey; }
        public void   setSecretKey(String k)                { this.secretKey = k; }
        public String getBaseUrl()                          { return baseUrl; }
        public void   setBaseUrl(String baseUrl)            { this.baseUrl = baseUrl; }
        public String getIngestionEndpoint()                { return ingestionEndpoint; }
        public void   setIngestionEndpoint(String endpoint) { this.ingestionEndpoint = endpoint; }

        @Override
        public String toString() {
            return "ProjectConfig{pk=" + maskKey(publicKey) + ", sk=" + maskKey(secretKey)
                    + ", baseUrl=" + baseUrl + "}";
        }

        private static String maskKey(String key) {
            if (key == null || key.length() < 12) return "(null or short)";
            return key.substring(0, 8) + "..." + key.substring(key.length() - 4);
        }
    }

    @PostConstruct
    public void logConfig() {
        logger.info("=== LANGFUSE CONFIG LOADED ===");
        logger.info("  baseUrl           = {}", baseUrl);
        logger.info("  ingestionEndpoint = {}", ingestionEndpoint);
        logger.info("  default publicKey = {}", maskKey(publicKey));
        logger.info("  default secretKey = {}", maskKey(secretKey));
        logger.info("  projects map size = {}", projects.size());
        if (projects.isEmpty()) {
            logger.warn("  *** NO PROJECTS CONFIGURED — all nodes use default credentials ***");
            logger.warn("  *** Verify application.properties has langfuse.projects.<node>.public-key ***");
        } else {
            for (Map.Entry<String, ProjectConfig> entry : projects.entrySet()) {
                logger.info("  project[{}] = {}", entry.getKey(), entry.getValue());
            }
        }
        logger.info("=== END LANGFUSE CONFIG ===");
    }

    public ProjectConfig getProjectForNode(String nodeName) {
        logger.info("ROUTING: getProjectForNode('{}'), available projects={}", nodeName, projects.keySet());

        if (nodeName != null && !nodeName.isBlank()) {
            String key = nodeName.toLowerCase().trim();

            ProjectConfig found = projects.get(key);
            if (found != null && !hasCredentials(found)) {
                // A project declared but never given keys is a half-finished
                // config, not a routing target. Falling through to the default
                // keeps the trace visible instead of failing the whole batch.
                logger.warn("ROUTING: project '{}' is configured without credentials — using DEFAULT", key);
                found = null;
            }
            if (found != null) {
                logger.info("ROUTING: MATCHED project '{}' → pk={}", key,
                        found.getPublicKey() != null
                                ? found.getPublicKey().substring(0, Math.min(12, found.getPublicKey().length())) + "..."
                                : "null");
                return found;
            }
            logger.warn("ROUTING: NO MATCH for '{}'. Keys in map: {}. Falling back to DEFAULT.", key, projects.keySet());
        } else {
            logger.info("ROUTING: nodeName is null/blank → DEFAULT");
        }

        ProjectConfig def = new ProjectConfig();
        def.setPublicKey(publicKey);
        def.setSecretKey(secretKey);
        return def;
    }

    private static boolean hasCredentials(ProjectConfig p) {
        return p.getPublicKey() != null && !p.getPublicKey().isBlank()
            && p.getSecretKey() != null && !p.getSecretKey().isBlank();
    }

    public String getIngestionUrl(ProjectConfig project) {
        String base = (project != null && project.getBaseUrl() != null && !project.getBaseUrl().isBlank())
                ? project.getBaseUrl() : baseUrl;
        String endpoint = (project != null && project.getIngestionEndpoint() != null && !project.getIngestionEndpoint().isBlank())
                ? project.getIngestionEndpoint() : ingestionEndpoint;
        return joinUrl(base, endpoint);
    }

    public String getFullIngestionUrl() {
        return joinUrl(baseUrl, ingestionEndpoint);
    }

    private String joinUrl(String base, String path) {
        if (base == null) base = "";
        if (path == null) path = "";
        String cleanBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        String cleanPath = path.startsWith("/") ? path : "/" + path;
        return cleanBase + cleanPath;
    }

    private static String maskKey(String key) {
        if (key == null || key.length() < 12) return "(null or short)";
        return key.substring(0, 8) + "..." + key.substring(key.length() - 4);
    }

    public String getBaseUrl()                     { return baseUrl; }
    public void   setBaseUrl(String baseUrl)       { this.baseUrl = baseUrl; }
    public String getIngestionEndpoint()           { return ingestionEndpoint; }
    public void   setIngestionEndpoint(String e)   { this.ingestionEndpoint = e; }
    public String getPublicKey()                   { return publicKey; }
    public void   setPublicKey(String publicKey)   { this.publicKey = publicKey; }
    public String getSecretKey()                   { return secretKey; }
    public void   setSecretKey(String secretKey)   { this.secretKey = secretKey; }
    public Map<String, ProjectConfig> getProjects(){ return projects; }
    public void setProjects(Map<String, ProjectConfig> p) { this.projects = p; }
}