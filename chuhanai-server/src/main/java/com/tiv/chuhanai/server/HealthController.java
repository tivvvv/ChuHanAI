package com.tiv.chuhanai.server;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1")
class HealthController {

    @Value("${app.version:0.1.0}")
    private String version;

    @GetMapping("/health")
    HealthPayload health() {
        return new HealthPayload("UP", Instant.now().toEpochMilli(), version);
    }
}
