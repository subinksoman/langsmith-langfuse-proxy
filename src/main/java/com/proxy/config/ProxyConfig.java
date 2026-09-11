package com.proxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "proxy")
public class ProxyConfig {
    private boolean logRequests = true;
    private boolean logResponses = true;
    private int timeoutSeconds = 30;
    private int maxConnections = 100;
    private int maxConnectionsPerRoute = 20;

    public boolean isLogRequests()              { return logRequests; }
    public void setLogRequests(boolean v)       { this.logRequests = v; }
    public boolean isLogResponses()             { return logResponses; }
    public void setLogResponses(boolean v)      { this.logResponses = v; }
    public int getTimeoutSeconds()              { return timeoutSeconds; }
    public void setTimeoutSeconds(int v)        { this.timeoutSeconds = v; }
    public int getMaxConnections()              { return maxConnections; }
    public void setMaxConnections(int v)        { this.maxConnections = v; }
    public int getMaxConnectionsPerRoute()      { return maxConnectionsPerRoute; }
    public void setMaxConnectionsPerRoute(int v){ this.maxConnectionsPerRoute = v; }
}
