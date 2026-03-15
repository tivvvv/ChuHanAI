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
import static org.mockito.ArgumentMatchers.anyInt;
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
        when(persistenceService.loadRecentChats(anyString(), anyInt())).thenReturn(List.of());
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

    @Test
    void shouldFinishGameWhenDrawIsAccepted() throws Exception {
        EmbeddedChannel firstChannel = new EmbeddedChannel();
        EmbeddedChannel secondChannel = new EmbeddedChannel();
        AuthenticatedSession first = connect(firstChannel);
        AuthenticatedSession second = connect(secondChannel);
        MatchContext match = matchPlayers(first, second, firstChannel, secondChannel);
        String roomId = match.roomId();

        gameCoordinator.handle(first.sessionId(), inbound(first.sessionId(), 2L, MessageType.DRAW_REQUEST, roomId, Map.of()));
        List<OutboundMessage> drawPendingForFirst = drainMessages(firstChannel);
        List<OutboundMessage> drawPendingForSecond = drainMessages(secondChannel);

        assertTrue(drawPendingForFirst.stream().anyMatch(message -> message.type() == MessageType.DRAW_PENDING));
        assertTrue(drawPendingForSecond.stream().anyMatch(message -> message.type() == MessageType.DRAW_PENDING));

        gameCoordinator.handle(second.sessionId(), inbound(second.sessionId(), 2L, MessageType.DRAW_RESPONSE, roomId, Map.of("decision", "ACCEPT")));
        List<OutboundMessage> firstMessages = drainMessages(firstChannel);
        List<OutboundMessage> secondMessages = drainMessages(secondChannel);

        assertTrue(firstMessages.stream().anyMatch(message -> message.type() == MessageType.DRAW_RESPONSE && Boolean.TRUE.equals(message.success())));
        assertTrue(firstMessages.stream().anyMatch(message -> message.type() == MessageType.GAME_OVER
                && payload(message, GameOverPayload.class).endReason() == FinishReason.DRAW_AGREED));
        assertTrue(secondMessages.stream().anyMatch(message -> message.type() == MessageType.GAME_OVER
                && payload(message, GameOverPayload.class).endReason() == FinishReason.DRAW_AGREED));
        assertNull(sessionService.currentRoomId(first.sessionId()));
        assertNull(sessionService.currentRoomId(second.sessionId()));
    }

    @Test
    void shouldUndoLatestMoveWhenUndoIsAccepted() throws Exception {
        EmbeddedChannel firstChannel = new EmbeddedChannel();
        EmbeddedChannel secondChannel = new EmbeddedChannel();
        AuthenticatedSession first = connect(firstChannel);
        AuthenticatedSession second = connect(secondChannel);
        MatchContext match = matchPlayers(first, second, firstChannel, secondChannel);
        String roomId = match.roomId();

        AuthenticatedSession redPlayer = match.firstSide() == Side.RED ? first : second;
        AuthenticatedSession blackPlayer = redPlayer == first ? second : first;
        EmbeddedChannel redChannel = redPlayer == first ? firstChannel : secondChannel;
        EmbeddedChannel blackChannel = redPlayer == first ? secondChannel : firstChannel;

        gameCoordinator.handle(redPlayer.sessionId(), inbound(redPlayer.sessionId(), 2L, MessageType.MOVE_REQUEST, roomId,
                Map.of("moveNo", 1, "from", "a3", "to", "a4", "piece", "PAWN")));
        List<OutboundMessage> redMoveMessages = drainMessages(redChannel);
        List<OutboundMessage> blackMoveMessages = drainMessages(blackChannel);
        assertTrue(redMoveMessages.stream().anyMatch(message -> message.type() == MessageType.MOVE_ACCEPTED));
        assertTrue(blackMoveMessages.stream().anyMatch(message -> message.type() == MessageType.MOVE_ACCEPTED));

        gameCoordinator.handle(blackPlayer.sessionId(), inbound(blackPlayer.sessionId(), 2L, MessageType.UNDO_REQUEST, roomId, Map.of()));
        List<OutboundMessage> undoPendingForBlack = drainMessages(blackChannel);
        List<OutboundMessage> undoPendingForRed = drainMessages(redChannel);
        assertTrue(undoPendingForBlack.stream().anyMatch(message -> message.type() == MessageType.UNDO_PENDING));
        assertTrue(undoPendingForRed.stream().anyMatch(message -> message.type() == MessageType.UNDO_PENDING));

        gameCoordinator.handle(redPlayer.sessionId(), inbound(redPlayer.sessionId(), 3L, MessageType.UNDO_RESPONSE, roomId, Map.of("decision", "ACCEPT")));
        List<OutboundMessage> redUndoMessages = drainMessages(redChannel);
        List<OutboundMessage> blackUndoMessages = drainMessages(blackChannel);

        UndoAppliedPayload undoApplied = redUndoMessages.stream()
                .filter(message -> message.type() == MessageType.UNDO_APPLIED)
                .map(message -> payload(message, UndoAppliedPayload.class))
                .findFirst()
                .orElseThrow();
        assertEquals(0, undoApplied.moveNo());
        assertEquals(Side.RED, undoApplied.currentTurn());
        assertTrue(blackUndoMessages.stream().anyMatch(message -> message.type() == MessageType.UNDO_APPLIED));
    }

    @Test
    void shouldIncludeRecentChatsInSnapshot() throws Exception {
        EmbeddedChannel firstChannel = new EmbeddedChannel();
        EmbeddedChannel secondChannel = new EmbeddedChannel();
        AuthenticatedSession first = connect(firstChannel);
        AuthenticatedSession second = connect(secondChannel);
        MatchContext match = matchPlayers(first, second, firstChannel, secondChannel);
        String roomId = match.roomId();

        when(persistenceService.loadRecentChats(roomId, 20)).thenReturn(List.of(
                new ChatRecordView(first.sessionId(), "你好", Instant.now().toEpochMilli()),
                new ChatRecordView(second.sessionId(), "开始吧", Instant.now().toEpochMilli())
        ));

        gameCoordinator.handle(first.sessionId(), inbound(first.sessionId(), 2L, MessageType.SNAPSHOT_SYNC, roomId, Map.of("lastKnownMoveNo", 0)));
        List<OutboundMessage> snapshotMessages = drainMessages(firstChannel);

        SnapshotPayload snapshot = snapshotMessages.stream()
                .filter(message -> message.type() == MessageType.SNAPSHOT_SYNCED)
                .map(message -> payload(message, SnapshotPayload.class))
                .findFirst()
                .orElseThrow();
        assertEquals(2, snapshot.recentChats().size());
        assertEquals("你好", snapshot.recentChats().getFirst().content());
    }

    private MatchContext matchPlayers(AuthenticatedSession first, AuthenticatedSession second,
                                      EmbeddedChannel firstChannel, EmbeddedChannel secondChannel) throws Exception {
        gameCoordinator.handle(first.sessionId(), inbound(first.sessionId(), 1L, MessageType.MATCH_JOIN, null, Map.of("clientVersion", "test-client")));
        drainMessages(firstChannel);

        gameCoordinator.handle(second.sessionId(), inbound(second.sessionId(), 1L, MessageType.MATCH_JOIN, null, Map.of("clientVersion", "test-client")));
        List<OutboundMessage> firstMessages = drainMessages(firstChannel);
        List<OutboundMessage> secondMessages = drainMessages(secondChannel);
        Side firstSide = firstMessages.stream()
                .filter(message -> message.type() == MessageType.MATCH_SUCCESS)
                .map(message -> payload(message, MatchSuccessPayload.class).mySide())
                .findFirst()
                .orElse(null);
        Side secondSide = secondMessages.stream()
                .filter(message -> message.type() == MessageType.MATCH_SUCCESS)
                .map(message -> payload(message, MatchSuccessPayload.class).mySide())
                .findFirst()
                .orElse(null);
        return new MatchContext(sessionService.currentRoomId(first.sessionId()), firstSide, secondSide);
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

    private <T> T payload(OutboundMessage message, Class<T> type) {
        return objectMapper.convertValue(message.payload(), type);
    }

    private record MatchContext(String roomId, Side firstSide, Side secondSide) {
    }
}
