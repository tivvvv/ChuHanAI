package com.tiv.chuhanai.client.net;

import com.tiv.chuhanai.client.config.ClientConfig;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class HealthApiClient {
    private final ClientConfig config;
    private final HttpClient httpClient;

    public HealthApiClient(ClientConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    public CompletableFuture<HealthCheckResult> check() {
        HttpRequest request = HttpRequest.newBuilder(config.healthUri())
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> new HealthCheckResult(response.statusCode(), response.body()))
                .exceptionally(throwable -> {
                    Throwable cause = throwable instanceof CompletionException ? throwable.getCause() : throwable;
                    if (cause instanceof IOException || cause instanceof InterruptedException) {
                        return new HealthCheckResult(0, cause.getMessage());
                    }
                    return new HealthCheckResult(0, throwable.getMessage());
                });
    }

    public record HealthCheckResult(
            int statusCode,
            String body
    ) {
        public boolean ok() {
            return statusCode >= 200 && statusCode < 300;
        }
    }
}
