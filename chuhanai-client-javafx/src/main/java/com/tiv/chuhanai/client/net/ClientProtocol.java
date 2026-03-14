package com.tiv.chuhanai.client.net;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public final class ClientProtocol {
    private ClientProtocol() {
    }

    public enum MessageType {
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

    public enum Side {
        RED,
        BLACK;

        public Side opposite() {
            return this == RED ? BLACK : RED;
        }
    }

    public enum RoomStatus {
        WAITING,
        PLAYING,
        FINISHED,
        CANCELLED
    }

    public enum FinishReason {
        CHECKMATE,
        RESIGN,
        TIMEOUT,
        DRAW_AGREED,
        DRAW_STALEMATE,
        RECONNECT_TIMEOUT
    }

    public enum ControlType {
        UNDO,
        DRAW
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClientEnvelope(
            String msgId,
            Long seq,
            Long ackSeq,
            MessageType type,
            String roomId,
            String sessionId,
            Long timestampMs,
            Object payload
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ServerEnvelope(
            String msgId,
            String replyToMsgId,
            Long seq,
            Long ackSeq,
            MessageType type,
            String roomId,
            String sessionId,
            Long timestampMs,
            Boolean success,
            ErrorPayload error,
            JsonNode payload
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorPayload(
            String code,
            String message,
            Boolean retryable
    ) {
    }

    public record ConnectPayload(
            String protocolVersion,
            String clientVersion,
            String sessionId,
            String resumeToken
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ConnectedPayload(
            String sessionId,
            String resumeToken,
            Boolean resumed,
            String protocolVersion,
            String serverTimeZone
    ) {
    }

    public record MatchJoinPayload(
            String clientVersion
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MatchSuccessPayload(
            String roomId,
            Side mySide,
            String opponentSessionId,
            Integer baseTimeMs,
            Integer incrementMs,
            String initialFen
    ) {
    }

    public record MoveRequestPayload(
            Integer moveNo,
            String from,
            String to,
            String piece
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoveAcceptedPayload(
            Integer moveNo,
            String from,
            String to,
            String piece,
            String capturedPiece,
            String boardFenAfter,
            Side nextTurn,
            Integer redTimeLeftMs,
            Integer blackTimeLeftMs
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MoveRejectedPayload(
            String authoritativeFen,
            Integer expectedMoveNo
    ) {
    }

    public record ChatSendPayload(
            String content
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChatBroadcastPayload(
            String messageId,
            String senderSessionId,
            String content,
            Long sentAtMs
    ) {
    }

    public record ControlDecisionPayload(
            String decision
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PendingControlPayload(
            ControlType controlType,
            String fromSessionId,
            Long expireAtMs
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UndoAppliedPayload(
            Integer moveNo,
            String boardFen,
            Side currentTurn,
            Integer redTimeLeftMs,
            Integer blackTimeLeftMs
    ) {
    }

    public record SnapshotSyncPayload(
            Integer lastKnownMoveNo
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SnapshotPayload(
            SnapshotRoomPayload room,
            List<SnapshotMovePayload> recentMoves,
            PendingControlPayload pendingControlEvent
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SnapshotRoomPayload(
            String roomId,
            RoomStatus status,
            Side currentTurn,
            String boardFen,
            Integer moveNo,
            Integer redTimeLeftMs,
            Integer blackTimeLeftMs
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SnapshotMovePayload(
            Integer moveNo,
            String from,
            String to,
            String piece,
            Long createdAtMs
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimeSyncPayload(
            Integer moveNo,
            Side currentTurn,
            Integer redTimeLeftMs,
            Integer blackTimeLeftMs
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameOverPayload(
            Side winnerSide,
            FinishReason endReason,
            Integer finalMoveNo,
            String finalFen,
            Long endedAtMs
    ) {
    }
}
