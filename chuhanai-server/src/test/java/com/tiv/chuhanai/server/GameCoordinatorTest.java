package com.tiv.chuhanai.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GameCoordinatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SessionService sessionService = new SessionService();
    private final PersistenceService persistenceService = mock(PersistenceService.class);

    private ScheduledExecutorService scheduler;
    private GameCoordinator gameCoordinator;

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        gameCoordinator = new GameCoordinator(sessionService, new XiangqiEngine(), persistenceService, objectMapper, scheduler);
        when(persistenceService.loadIdempotent(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    @Test
    void shouldAllowSessionToRejoinMatchQueueAfterResign() throws Exception {
        EmbeddedChannel firstChannel = new EmbeddedChannel();
        EmbeddedChannel secondChannel = new EmbeddedChannel();
        AuthenticatedSession first = connect(firstChannel);
        AuthenticatedSession second = connect(secondChannel);

        gameCoordinator.handle(first.sessionId(), inbound(first.sessionId(), 1L, MessageType.MATCH_JOIN, null, Map.of("clientVersion", "test-client")));
        drainMessages(firstChannel);

        gameCoordinator.handle(second.sessionId(), inbound(second.sessionId(), 1L, MessageType.MATCH_JOIN, null, Map.of("clientVersion", "test-client")));
        List<OutboundMessage> matchedFirstMessages = drainMessages(firstChannel);
        List<OutboundMessage> matchedSecondMessages = drainMessages(secondChannel);

        assertTrue(matchedFirstMessages.stream().anyMatch(message -> message.type() == MessageType.MATCH_SUCCESS && Boolean.TRUE.equals(message.success())));
        assertTrue(matchedSecondMessages.stream().anyMatch(message -> message.type() == MessageType.MATCH_SUCCESS && Boolean.TRUE.equals(message.success())));

        String roomId = sessionService.currentRoomId(first.sessionId());
        assertNotNull(roomId);
        assertEquals(roomId, sessionService.currentRoomId(second.sessionId()));

        gameCoordinator.handle(first.sessionId(), inbound(first.sessionId(), 2L, MessageType.RESIGN, roomId, Map.of()));
        List<OutboundMessage> resignMessages = drainMessages(firstChannel);
        List<OutboundMessage> opponentMessages = drainMessages(secondChannel);

        assertTrue(resignMessages.stream().anyMatch(message -> message.type() == MessageType.RESIGN && Boolean.TRUE.equals(message.success())));
        assertTrue(resignMessages.stream().anyMatch(message -> message.type() == MessageType.GAME_OVER && Boolean.TRUE.equals(message.success())));
        assertTrue(opponentMessages.stream().anyMatch(message -> message.type() == MessageType.GAME_OVER && Boolean.TRUE.equals(message.success())));
        assertNull(sessionService.currentRoomId(first.sessionId()));
        assertNull(sessionService.currentRoomId(second.sessionId()));

        gameCoordinator.handle(first.sessionId(), inbound(first.sessionId(), 3L, MessageType.MATCH_JOIN, null, Map.of("clientVersion", "test-client")));
        List<OutboundMessage> rematchMessages = drainMessages(firstChannel);

        assertTrue(rematchMessages.stream().anyMatch(message -> message.type() == MessageType.MATCH_JOINED && Boolean.TRUE.equals(message.success())));
    }

    private AuthenticatedSession connect(EmbeddedChannel channel) {
        return sessionService.connect(new ConnectPayload(ProtocolModels.PROTOCOL_VERSION, "test-client", null, null), channel);
    }

    private InboundMessage inbound(String sessionId, long seq, MessageType type, String roomId, Map<String, Object> payload) {
        return new InboundMessage(
                "c-" + UUID.randomUUID(),
                seq,
                0L,
                type,
                roomId,
                sessionId,
                Instant.now().toEpochMilli(),
                objectMapper.valueToTree(payload)
        );
    }

    private List<OutboundMessage> drainMessages(EmbeddedChannel channel) throws Exception {
        List<OutboundMessage> messages = new ArrayList<>();
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            if (outbound instanceof TextWebSocketFrame frame) {
                messages.add(objectMapper.readValue(frame.text(), OutboundMessage.class));
            }
        }
        return messages;
    }
}
