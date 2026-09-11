package com.proxy.service;

import com.bazaarvoice.jolt.Chainr;
import com.bazaarvoice.jolt.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class JoltTransformationService {

    private static final Logger logger = LoggerFactory.getLogger(JoltTransformationService.class);

    private final ObjectMapper objectMapper;

    private Chainr filterLlmRunsSpec;
    private Chainr extractLlmFieldsSpec;
    private Chainr traceCreateSpec;
    private Chainr generationCreateSpec;
    private Chainr generationUpdateSpec;
    private Chainr traceCreateFinalSpec;

    @Autowired
    public JoltTransformationService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        try {
            filterLlmRunsSpec = loadJoltSpec("jolt/spec-01-filter-llm-runs.json");
            extractLlmFieldsSpec = loadJoltSpec("jolt/spec-02-extract-llm-fields.json");
            traceCreateSpec = loadJoltSpec("jolt/spec-03-trace-create.json");
            generationCreateSpec = loadJoltSpec("jolt/spec-04-generation-create.json");
            generationUpdateSpec = loadJoltSpec("jolt/spec-05-generation-update.json");
            traceCreateFinalSpec = loadJoltSpec("jolt/spec-06-trace-create-final.json");
            logger.info("JOLT specs loaded successfully");
        } catch (Exception e) {
            logger.warn("Could not load JOLT specs, falling back to Java transformation: {}", e.getMessage());
        }
    }

    private Chainr loadJoltSpec(String resourcePath) {
        try {
            ClassPathResource resource = new ClassPathResource(resourcePath);
            InputStream inputStream = resource.getInputStream();
            List<Object> spec = JsonUtils.jsonToList(inputStream);
            return Chainr.fromSpec(spec);
        } catch (Exception e) {
            logger.error("Failed to load JOLT spec: {}", resourcePath, e);
            return null;
        }
    }

    public Object filterLlmRuns(Object input) {
        if (filterLlmRunsSpec == null) return null;
        return filterLlmRunsSpec.transform(input);
    }

    public Object extractLlmFields(Object llmRun) {
        if (extractLlmFieldsSpec == null) return llmRun;
        return extractLlmFieldsSpec.transform(llmRun);
    }

    public Object toTraceCreate(Object extractedFields) {
        if (traceCreateSpec == null) return extractedFields;
        return traceCreateSpec.transform(extractedFields);
    }

    public Object toGenerationCreate(Object extractedFields) {
        if (generationCreateSpec == null) return extractedFields;
        return generationCreateSpec.transform(extractedFields);
    }

    public Object toGenerationUpdate(Object extractedFields) {
        if (generationUpdateSpec == null) return extractedFields;
        return generationUpdateSpec.transform(extractedFields);
    }

    public Object toTraceCreateFinal(Object extractedFields) {
        if (traceCreateFinalSpec == null) return extractedFields;
        return traceCreateFinalSpec.transform(extractedFields);
    }

    public Map<String, Object> jsonNodeToMap(JsonNode node) {
        try {
            return objectMapper.convertValue(node, Map.class);
        } catch (Exception e) {
            logger.error("Error converting JsonNode to Map", e);
            return new HashMap<>();
        }
    }

    public JsonNode mapToJsonNode(Object map) {
        try {
            return objectMapper.valueToTree(map);
        } catch (Exception e) {
            logger.error("Error converting Map to JsonNode", e);
            return objectMapper.createObjectNode();
        }
    }
}
