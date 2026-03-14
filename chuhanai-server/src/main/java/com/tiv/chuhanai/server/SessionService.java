package com.tiv.chuhanai.server;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
class SessionService {

    private final ConcurrentMap<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> channelSessions = new ConcurrentHashMap<>();

    AuthenticatedSession connect(ConnectPayload payload, Channel channel) {
        if (payload == null || payload.protocolVersion() == null || payload.clientVersion() == null) {
            throw new IllegalArgumentException("缺少连接首包字段");
        }
        if (!Objects.equals(payload.protocolVersion(), ProtocolModels.PROTOCOL_VERSION)) {
            throw new IllegalArgumentException("协议版本不支持");
        }

        AuthenticatedSession authenticatedSession;
        if (payload.sessionId() == null || payload.resumeToken() == null) {
            SessionState created = new SessionState(IdGenerator.sessionId(), IdGenerator.resumeToken(), payload.clientVersion());
            sessions.put(created.sessionId, created);
            authenticatedSession = new AuthenticatedSession(created.sessionId, created.resumeToken, created.clientVersion, false);
        } else {
            SessionState existed = sessions.get(payload.sessionId());
            if (existed == null || !Objects.equals(existed.resumeToken, payload.resumeToken())) {
                throw new IllegalArgumentException("会话无效");
            }
            existed.clientVersion = payload.clientVersion();
            authenticatedSession = new AuthenticatedSession(existed.sessionId, existed.resumeToken, existed.clientVersion, true);
        }

        bindChannel(authenticatedSession.sessionId(), channel);
        return authenticatedSession;
    }

    boolean acceptSeq(String sessionId, long seq) {
        SessionState state = requireSession(sessionId);
        long current = state.lastClientSeq.get();
        if (seq <= current) {
            return false;
        }
        state.lastClientSeq.set(seq);
        state.lastSeenAtMs = Instant.now().toEpochMilli();
        return true;
    }

    void touch(String sessionId) {
        requireSession(sessionId).lastSeenAtMs = Instant.now().toEpochMilli();
    }

    Optional<String> sessionId(Channel channel) {
        return Optional.ofNullable(channelSessions.get(channel.id().asLongText()));
    }

    Optional<String> unbind(Channel channel) {
        String channelId = channel.id().asLongText();
        String sessionId = channelSessions.remove(channelId);
        if (sessionId == null) {
            return Optional.empty();
        }
        SessionState state = sessions.get(sessionId);
        if (state != null && state.channel == channel) {
            state.channel = null;
            state.online = false;
            state.lastSeenAtMs = Instant.now().toEpochMilli();
        }
        return Optional.of(sessionId);
    }

    boolean push(String sessionId, String payload) {
        SessionState state = sessions.get(sessionId);
        if (state == null || state.channel == null || !state.channel.isActive()) {
            return false;
        }
        state.lastSeenAtMs = Instant.now().toEpochMilli();
        state.channel.writeAndFlush(new TextWebSocketFrame(payload));
        return true;
    }

    void assignRoom(String sessionId, String roomId) {
        requireSession(sessionId).currentRoomId = roomId;
    }

    void clearRoom(String sessionId, String roomId) {
        SessionState state = sessions.get(sessionId);
        if (state != null && Objects.equals(state.currentRoomId, roomId)) {
            state.currentRoomId = null;
        }
    }

    String currentRoomId(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state == null ? null : state.currentRoomId;
    }

    boolean isOnline(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state != null && state.online;
    }

    private void bindChannel(String sessionId, Channel channel) {
        SessionState state = requireSession(sessionId);
        Channel previous = state.channel;
        if (previous != null && previous != channel && previous.isActive()) {
            previous.close();
        }
        state.channel = channel;
        state.online = true;
        state.lastSeenAtMs = Instant.now().toEpochMilli();
        channelSessions.put(channel.id().asLongText(), sessionId);
    }

    private SessionState requireSession(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            throw new IllegalArgumentException("会话不存在");
        }
        return state;
    }

    private static final class SessionState {
        private final String sessionId;
        private final String resumeToken;
        private final AtomicLong lastClientSeq = new AtomicLong();
        private volatile String clientVersion;
        private volatile Channel channel;
        private volatile String currentRoomId;
        private volatile boolean online;
        private volatile long lastSeenAtMs;

        private SessionState(String sessionId, String resumeToken, String clientVersion) {
            this.sessionId = sessionId;
            this.resumeToken = resumeToken;
            this.clientVersion = clientVersion;
            this.lastSeenAtMs = Instant.now().toEpochMilli();
        }
    }
}
