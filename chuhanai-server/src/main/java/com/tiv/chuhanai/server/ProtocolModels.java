package com.tiv.chuhanai.server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;

final class ProtocolModels {

    static final String PROTOCOL_VERSION = "1.0";

    private ProtocolModels() {
    }
}

enum MessageType {
    CONNECT,
    CONNECTED,
    MATCH_JOIN,
    MATCH_JOINED,
    MATCH_CANCEL,
    MATCH_CANCELLED,
    MATCH_SUCCESS,
    MOVE_REQUEST,
    MOVE_ACCEPTED,
    MOVE_REJECTED,
    UNDO_REQUEST,
    UNDO_PENDING,
    UNDO_RESPONSE,
    UNDO_APPLIED,
    UNDO_REJECTED,
    DRAW_REQUEST,
    DRAW_PENDING,
    DRAW_RESPONSE,
    DRAW_REJECTED,
    RESIGN,
    CHAT_SEND,
    CHAT_ACCEPTED,
    CHAT_BROADCAST,
    SNAPSHOT_SYNC,
    SNAPSHOT_SYNCED,
    TIME_SYNC,
    GAME_OVER,
    PING,
    PONG
}

enum Side {
    RED,
    BLACK;

    Side opposite() {
        return this == RED ? BLACK : RED;
    }
}

enum RoomStatus {
    WAITING,
    PLAYING,
    FINISHED,
    CANCELLED
}

enum FinishReason {
    CHECKMATE,
    RESIGN,
    TIMEOUT,
    DRAW_AGREED,
    DRAW_STALEMATE,
    RECONNECT_TIMEOUT
}

enum ControlType {
    UNDO,
    DRAW
}

record InboundMessage(
        String msgId,
        Long seq,
        Long ackSeq,
        MessageType type,
        String roomId,
        String sessionId,
        Long timestampMs,
        JsonNode payload
) {
}

record ConnectPayload(
        String protocolVersion,
        String clientVersion,
        String sessionId,
        String resumeToken
) {
}

record AuthenticatedSession(
        String sessionId,
        String resumeToken,
        String clientVersion,
        boolean resumed
) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record ErrorPayload(
        String code,
        String message,
        boolean retryable
) {
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record OutboundMessage(
        String msgId,
        String replyToMsgId,
        MessageType type,
        String roomId,
        String sessionId,
        Long timestampMs,
        Boolean success,
        ErrorPayload error,
        Object payload
) {
    static OutboundMessage success(MessageType type, String roomId, String replyToMsgId, Object payload) {
        return new OutboundMessage(IdGenerator.serverMessageId(), replyToMsgId, type, roomId, "SERVER",
                Instant.now().toEpochMilli(), true, null, payload);
    }

    static OutboundMessage failure(MessageType type, String roomId, String replyToMsgId,
                                   String code, String message, boolean retryable, Object payload) {
        return new OutboundMessage(IdGenerator.serverMessageId(), replyToMsgId, type, roomId, "SERVER",
                Instant.now().toEpochMilli(), false, new ErrorPayload(code, message, retryable), payload);
    }
}

record ConnectedPayload(
        String sessionId,
        String resumeToken,
        boolean resumed,
        String protocolVersion,
        String serverTimeZone
) {
}

record MatchJoinedPayload(
        String sessionId,
        String status
) {
}

record MatchSuccessPayload(
        String roomId,
        Side mySide,
        String opponentSessionId,
        int baseTimeMs,
        int incrementMs,
        String initialFen
) {
}

record MoveRequestPayload(
        Integer moveNo,
        String from,
        String to,
        String piece
) {
}

record MoveAcceptedPayload(
        int moveNo,
        String from,
        String to,
        String piece,
        String capturedPiece,
        String boardFenAfter,
        Side nextTurn,
        int redTimeLeftMs,
        int blackTimeLeftMs
) {
}

record TimeSyncPayload(
        int moveNo,
        Side currentTurn,
        int redTimeLeftMs,
        int blackTimeLeftMs
) {
}

record ControlDecisionPayload(
        String decision
) {
}

record PendingControlPayload(
        ControlType controlType,
        String fromSessionId,
        long expireAtMs
) {
}

record UndoAppliedPayload(
        int moveNo,
        String boardFen,
        Side currentTurn,
        int redTimeLeftMs,
        int blackTimeLeftMs
) {
}

record ChatPayload(
        String content
) {
}

record ChatBroadcastPayload(
        String messageId,
        String senderSessionId,
        String content,
        long sentAtMs
) {
}

record SnapshotRoomPayload(
        String roomId,
        RoomStatus status,
        Side currentTurn,
        String boardFen,
        int moveNo,
        int redTimeLeftMs,
        int blackTimeLeftMs
) {
}

record SnapshotMovePayload(
        int moveNo,
        String from,
        String to,
        String piece,
        long createdAtMs
) {
}

record SnapshotPayload(
        SnapshotRoomPayload room,
        List<SnapshotMovePayload> recentMoves,
        PendingControlPayload pendingControlEvent
) {
}

record GameOverPayload(
        Side winnerSide,
        FinishReason endReason,
        int finalMoveNo,
        String finalFen,
        long endedAtMs
) {
}

record HealthPayload(
        String status,
        long serverTimeMs,
        String version
) {
}

record IdempotentResult(
        MessageType responseType,
        boolean success,
        String responseJson
) {
}

record MoveRecordView(
        int moveNo,
        String fromPos,
        String toPos,
        String piece,
        long createdAtMs
) {
}

record ControlEvent(
        ControlType type,
        String msgId,
        String initiatorSessionId,
        String targetSessionId,
        long expireAtMs
) {
}

record ProtocolException(
        String code,
        String message,
        boolean retryable
) {
    RuntimeException asRuntime() {
        return new RuntimeException(message);
    }
}

record SimplePayload(
        Map<String, Object> values
) {
}
