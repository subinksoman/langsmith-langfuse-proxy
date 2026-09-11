package com.proxy.filter;

import com.proxy.config.ProxyConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@Order(1)
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingFilter.class);

    private final ProxyConfig proxyConfig;

    @Autowired
    public RequestLoggingFilter(ProxyConfig proxyConfig) {
        this.proxyConfig = proxyConfig;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                   HttpServletResponse response,
                                   FilterChain filterChain) throws ServletException, IOException {
        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse = new ContentCachingResponseWrapper(response);

        long startTime = System.currentTimeMillis();
        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            String requestUri = request.getRequestURI();
            String method = request.getMethod();
            int status = response.getStatus();

            if (proxyConfig.isLogRequests()) {
                // DEBUG, not INFO: the container healthcheck hits /health every
                // 15s, and one access line per request at INFO buries the
                // ingestion logs that are actually worth reading.
                logger.debug("{} {} - {} ({}ms)", method, requestUri, status, duration);
                if ("POST".equalsIgnoreCase(method)) {
                    byte[] requestBody = wrappedRequest.getContentAsByteArray();
                    if (requestBody.length > 0) {
                        String body = new String(requestBody, StandardCharsets.UTF_8);
                        if (body.length() > 1000) {
                            logger.debug("Request body (truncated): {}...", body.substring(0, 1000));
                        } else {
                            logger.debug("Request body: {}", body);
                        }
                    }
                }
            }
            wrappedResponse.copyBodyToResponse();
        }
    }
}
