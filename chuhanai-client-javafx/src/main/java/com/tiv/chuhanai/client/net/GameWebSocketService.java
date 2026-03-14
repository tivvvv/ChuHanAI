package com.tiv.chuhanai.client.net;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.tiv.chuhanai.client.config.ClientConfig;
import com.tiv.chuhanai.client.net.ClientProtocol.ConnectPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.ClientEnvelope;
import com.tiv.chuhanai.client.net.ClientProtocol.ConnectedPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.MessageType;
import com.tiv.chuhanai.client.net.ClientProtocol.ServerEnvelope;
import com.tiv.chuhanai.client.store.SessionStore.StoredSession;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class GameWebSocketService {
    private final ClientConfig config;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "client-ws-service");
        thread.setDaemon(true);
        return thread;
    });

    private final AtomicLong sequence = new AtomicLong();
    private volatile GameClient client;
    private volatile Listener listener;
    private volatile String sessionId;
    private volatile String resumeToken;
    private volatile long lastServerSeq;
    private volatile boolean expectedClose;
    private volatile ScheduledFuture<?> heartbeatFuture;
    private volatile Optional<StoredSession> pendingSession = Optional.empty();

    public GameWebSocketService(ClientConfig config) {
        this.config = config;
    }

    public CompletableFuture<Void> connect(Optional<StoredSession> storedSession, Listener listener) {
        this.listener = listener;
        return CompletableFuture.runAsync(() -> {
            try {
                close(true);
                pendingSession = storedSession;
                sequence.set(0);
                lastServerSeq = 0;
                sessionId = null;
                resumeToken = null;
                expectedClose = false;
                GameClient newClient = new GameClient(config.webSocketUri(), defaultHeaders());
                client = newClient;
                if (!newClient.connectBlocking(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("WebSocket 连接超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("WebSocket 连接被中断", e);
            }
        }, scheduler);
    }

    public synchronized void close(boolean manual) {
        expectedClose = manual;
        ScheduledFuture<?> currentHeartbeat = heartbeatFuture;
        heartbeatFuture = null;
        if (currentHeartbeat != null) {
            currentHeartbeat.cancel(false);
        }
        GameClient current = client;
        client = null;
        if (current != null) {
            try {
                current.closeBlocking();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public boolean isOpen() {
        return client != null && client.isOpen();
    }

    public String resumeToken() {
        return resumeToken;
    }

    public String send(MessageType type, String roomId, Object payload) {
        if (!isOpen()) {
            throw new IllegalStateException("WebSocket 尚未连接");
        }
        String msgId = "c-" + UUID.randomUUID();
        JsonNode payloadNode = ClientJson.MAPPER.valueToTree(payload);
        ClientEnvelope envelope = new ClientEnvelope(
                msgId,
                sequence.incrementAndGet(),
                lastServerSeq,
                type,
                roomId,
                sessionId,
                Instant.now().toEpochMilli(),
                payloadNode
        );
        client.send(writeJson(envelope));
        return msgId;
    }

    public void sendPing() {
        try {
            send(MessageType.PING, null, Map.of());
        } catch (IllegalStateException ignored) {
        }
    }

    public void shutdown() {
        close(true);
        scheduler.shutdownNow();
    }

    private String writeJson(Object value) {
        try {
            return ClientJson.MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize websocket message", e);
        }
    }

    private Map<String, String> defaultHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Protocol-Version", config.protocolVersion());
        headers.put("X-Client-Version", config.clientVersion());
        headers.put("X-Trace-Id", UUID.randomUUID().toString());
        return headers;
    }

    private void sendConnectFrame() {
        GameClient current = client;
        if (current == null || !current.isOpen()) {
            throw new IllegalStateException("WebSocket 尚未连接");
        }
        StoredSession stored = pendingSession.orElse(null);
        ClientEnvelope envelope = new ClientEnvelope(
                "c-" + UUID.randomUUID(),
                sequence.incrementAndGet(),
                lastServerSeq,
                MessageType.CONNECT,
                null,
                null,
                Instant.now().toEpochMilli(),
                new ConnectPayload(
                        config.protocolVersion(),
                        config.clientVersion(),
                        stored == null ? null : stored.sessionId(),
                        stored == null ? null : stored.resumeToken()
                )
        );
        current.send(writeJson(envelope));
    }

    private final class GameClient extends WebSocketClient {
        private GameClient(URI serverUri, Map<String, String> headers) {
            super(serverUri, headers);
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            sendConnectFrame();
        }

        @Override
        public void onMessage(String message) {
            try {
                ServerEnvelope envelope = ClientJson.MAPPER.readValue(message, ServerEnvelope.class);
                if (envelope.seq() != null) {
                    lastServerSeq = envelope.seq();
                }
                if (envelope.type() == MessageType.CONNECTED && Boolean.TRUE.equals(envelope.success())) {
                    ConnectedPayload payload = envelope.payload() == null
                            ? null
                            : ClientJson.MAPPER.convertValue(envelope.payload(), ConnectedPayload.class);
                    if (payload != null) {
                        sessionId = payload.sessionId();
                        resumeToken = payload.resumeToken();
                        if (listener != null) {
                            listener.onAuthenticated(sessionId, resumeToken);
                        }
                        ScheduledFuture<?> currentHeartbeat = heartbeatFuture;
                        if (currentHeartbeat != null) {
                            currentHeartbeat.cancel(false);
                        }
                        heartbeatFuture = scheduler.scheduleAtFixedRate(GameWebSocketService.this::sendPing, 5, 5, TimeUnit.SECONDS);
                    }
                }
                if (listener != null) {
                    listener.onMessage(envelope);
                }
            } catch (Exception e) {
                if (listener != null) {
                    listener.onError("解析服务端消息失败: " + e.getMessage());
                }
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            if (listener != null) {
                listener.onDisconnected(code, reason, remote, expectedClose);
            }
        }

        @Override
        public void onError(Exception ex) {
            if (listener != null) {
                listener.onError(ex == null ? "未知网络错误" : ex.getMessage());
            }
        }
    }

    public interface Listener {
        void onAuthenticated(String sessionId, String resumeToken);

        void onMessage(ServerEnvelope envelope);

        void onDisconnected(int code, String reason, boolean remote, boolean expected);

        void onError(String message);
    }
}
