package com.tiv.chuhanai.server;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class IdGenerator {

    private static final DateTimeFormatter ROOM_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final AtomicLong COUNTER = new AtomicLong();

    private IdGenerator() {
    }

    static String sessionId() {
        return "S_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
    }

    static String resumeToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    static String roomId() {
        long suffix = COUNTER.incrementAndGet() % 1000;
        return "R" + LocalDateTime.now().format(ROOM_FORMAT) + String.format("%03d", suffix);
    }

    static String serverMessageId() {
        return "srv-" + UUID.randomUUID();
    }

    static String chatMessageId() {
        return "c-" + UUID.randomUUID();
    }
}
