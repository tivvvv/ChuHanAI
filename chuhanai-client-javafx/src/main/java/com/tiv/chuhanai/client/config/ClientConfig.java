package com.tiv.chuhanai.client.config;

import java.net.URI;
import java.util.Optional;

public record ClientConfig(
        URI httpBaseUri,
        URI healthUri,
        URI webSocketUri,
        String protocolVersion,
        String clientVersion
) {

    public static ClientConfig load() {
        String httpBase = read("CHUHANAI_HTTP_BASE_URL", "http://localhost:8080");
        String wsUrl = Optional.ofNullable(System.getenv("CHUHANAI_WS_URL"))
                .orElseGet(() -> toRealtimeWebSocketUrl(httpBase));
        String healthUrl = Optional.ofNullable(System.getenv("CHUHANAI_HEALTH_URL"))
                .orElse(httpBase + "/api/v1/health");
        return new ClientConfig(
                URI.create(httpBase),
                URI.create(healthUrl),
                URI.create(wsUrl),
                read("CHUHANAI_PROTOCOL_VERSION", "1.0"),
                read("CHUHANAI_CLIENT_VERSION", "0.1.0")
        );
    }

    private static String read(String envName, String defaultValue) {
        String fromProperty = System.getProperty(envName);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty;
        }
        String fromEnv = System.getenv(envName);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv;
        }
        return defaultValue;
    }

    private static String toRealtimeWebSocketUrl(String httpBase) {
        URI httpUri = URI.create(httpBase);
        String scheme = "https".equalsIgnoreCase(httpUri.getScheme()) ? "wss" : "ws";
        int port = httpUri.getPort() > 0 ? httpUri.getPort() : ("https".equalsIgnoreCase(httpUri.getScheme()) ? 443 : 80);
        int realtimePort = readInt("CHUHANAI_REALTIME_PORT", port == 8080 ? 10090 : port);
        return scheme + "://" + httpUri.getHost() + ":" + realtimePort + "/ws/v1/game";
    }

    private static int readInt(String name, int defaultValue) {
        String value = read(name, Integer.toString(defaultValue));
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
