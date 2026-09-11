package com.proxy.listener;

import com.proxy.config.LangfuseConfig;
import com.proxy.service.LangfuseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StartupListener {
    private static final Logger logger = LoggerFactory.getLogger(StartupListener.class);

    private final LangfuseConfig langfuseConfig;
    private final LangfuseService langfuseService;

    @Value("${server.port:8080}")
    private int serverPort;

    @Autowired
    public StartupListener(LangfuseConfig langfuseConfig, LangfuseService langfuseService) {
        this.langfuseConfig = langfuseConfig;
        this.langfuseService = langfuseService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        logger.info("============================================");
        logger.info("LangSmith to Langfuse Proxy Started!");
        logger.info("Listening on port {}", serverPort);
        logger.info("============================================");
        logger.info("Default Langfuse URL: {}", langfuseConfig.getFullIngestionUrl());
        logger.info("Default Public Key:   {}", maskKey(langfuseConfig.getPublicKey()));
        logger.info("Default Secret Key:   {}", maskKey(langfuseConfig.getSecretKey()));

        Map<String, LangfuseConfig.ProjectConfig> projects = langfuseConfig.getProjects();
        if (projects.isEmpty()) {
            logger.info("Projects: (none — all nodes will use default credentials)");
        } else {
            logger.info("Projects ({} configured):", projects.size());
            for (Map.Entry<String, LangfuseConfig.ProjectConfig> entry : projects.entrySet()) {
                String nodeName = entry.getKey();
                LangfuseConfig.ProjectConfig project = entry.getValue();
                String projectUrl = langfuseConfig.getIngestionUrl(project);
                logger.info("  [{}] url={}, pk={}, sk={}",
                        nodeName, projectUrl,
                        maskKey(project.getPublicKey()),
                        maskKey(project.getSecretKey()));
            }
        }
        logger.info("============================================");
        logger.info("Proxy is ready to receive requests at /runs");
        logger.info("============================================");
    }

    private String maskKey(String key) {
        if (key == null || key.length() < 8) return "****";
        return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
    }
}
