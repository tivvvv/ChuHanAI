package com.tiv.chuhanai.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Service
class GameCoordinator {

    private static final int BASE_TIME_MS = 600_000;
    private static final int INCREMENT_MS = 15_000;
    private static final long CONTROL_TIMEOUT_MS = 15_000L;
    private static final long RECONNECT_TIMEOUT_MS = 30_000L;

    private final SessionService sessionService;
    private final XiangqiEngine xiangqiEngine;
    private final PersistenceService persistenceService;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService scheduler;

    private final Object queueLock = new Object();
    private final Deque<String> matchQueue = new ArrayDeque<>();
    private final Set<String> queuedSessions = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<String, RoomRuntime> rooms = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> roomTimeouts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> reconnectTimeouts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledFuture<?>> controlTimeouts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Deque<Long>> chatWindows = new ConcurrentHashMap<>();

    GameCoordinator(SessionService sessionService,
                    XiangqiEngine xiangqiEngine,
                    PersistenceService persistenceService,
                    ObjectMapper objectMapper,
                    ScheduledExecutorService scheduler) {
        this.sessionService = sessionService;
        this.xiangqiEngine = xiangqiEngine;
        this.persistenceService = persistenceService;
        this.objectMapper = objectMapper;
        this.scheduler = scheduler;
    }

    void onConnected(String sessionId) {
        String roomId = sessionService.currentRoomId(sessionId);
        if (roomId == null) {
            return;
        }
        RoomRuntime room = rooms.get(roomId);
        if (room == null) {
            return;
        }
        synchronized (room) {
            cancelReconnectTimeout(sessionId);
            room.disconnectDeadlineMs = 0L;
            scheduleTurnTimeout(room);
        }
    }

    void onDisconnected(String sessionId) {
        String roomId = sessionService.currentRoomId(sessionId);
        if (roomId == null) {
            cancelMatchIfQueued(sessionId, false);
            return;
        }
        RoomRuntime room = rooms.get(roomId);
        if (room == null) {
            return;
        }
        synchronized (room) {
            if (room.status != RoomStatus.PLAYING) {
                return;
            }
            room.disconnectDeadlineMs = Instant.now().toEpochMilli() + RECONNECT_TIMEOUT_MS;
            scheduleReconnectTimeout(sessionId, room);
        }
    }

    void handle(String boundSessionId, InboundMessage message) {
        if (message.msgId() == null || message.type() == null || message.timestampMs() == null) {
            send(boundSessionId, OutboundMessage.failure(MessageType.PONG, null, message.msgId(),
                    "INVALID_REQUEST", "消息格式不完整", false, null));
            return;
        }
        if (message.sessionId() != null && !Objects.equals(boundSessionId, message.sessionId())) {
            send(boundSessionId, OutboundMessage.failure(resolveErrorType(message.type()), message.roomId(), message.msgId(),
                    "UNAUTHORIZED", "sessionId 与连接不匹配", false, null));
            return;
        }

        try {
            switch (message.type()) {
                case MATCH_JOIN -> handleMatchJoin(boundSessionId, message);
                case MATCH_CANCEL -> handleMatchCancel(boundSessionId, message);
                case MOVE_REQUEST -> handleMove(boundSessionId, message);
                case UNDO_REQUEST -> handleControlRequest(boundSessionId, message, ControlType.UNDO);
                case DRAW_REQUEST -> handleControlRequest(boundSessionId, message, ControlType.DRAW);
                case UNDO_RESPONSE -> handleControlResponse(boundSessionId, message, ControlType.UNDO);
                case DRAW_RESPONSE -> handleControlResponse(boundSessionId, message, ControlType.DRAW);
                case RESIGN -> handleResign(boundSessionId, message);
                case CHAT_SEND -> handleChat(boundSessionId, message);
                case SNAPSHOT_SYNC -> handleSnapshot(boundSessionId, message);
                case PING -> send(boundSessionId, OutboundMessage.success(MessageType.PONG, message.roomId(), message.msgId(),
                        Map.of("serverTimeMs", Instant.now().toEpochMilli())));
                default -> send(boundSessionId, OutboundMessage.failure(resolveErrorType(message.type()), message.roomId(), message.msgId(),
                        "INVALID_REQUEST", "暂不支持的消息类型", false, null));
            }
        } catch (IllegalMoveException e) {
            send(boundSessionId, OutboundMessage.failure(resolveErrorType(message.type()), message.roomId(), message.msgId(),
                    e.code(), e.getMessage(), false, moveRejectPayload(message.roomId())));
        } catch (IllegalArgumentException e) {
            send(boundSessionId, OutboundMessage.failure(resolveErrorType(message.type()), message.roomId(), message.msgId(),
                    "INVALID_REQUEST", e.getMessage(), false, null));
        } catch (Exception e) {
            send(boundSessionId, OutboundMessage.failure(resolveErrorType(message.type()), message.roomId(), message.msgId(),
                    "SYSTEM_ERROR", "服务端处理失败", true, null));
        }
    }

    private void handleMatchJoin(String sessionId, InboundMessage message) {
        PersistenceScope scope = new PersistenceScope("MATCH", null, sessionId, message.msgId(), message.type());
        if (replayIfNeeded(scope, sessionId)) {
            return;
        }
        if (sessionService.currentRoomId(sessionId) != null) {
            OutboundMessage reply = OutboundMessage.failure(MessageType.MATCH_JOINED, null, message.msgId(),
                    "MATCH_ALREADY_IN_QUEUE", "当前已在房间中", false, null);
            persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), false, reply);
            send(sessionId, reply);
            return;
        }

        boolean queued;
        synchronized (queueLock) {
            queued = queuedSessions.add(sessionId);
            if (queued) {
                matchQueue.offerLast(sessionId);
            }
        }
        if (!queued) {
            OutboundMessage reply = OutboundMessage.failure(MessageType.MATCH_JOINED, null, message.msgId(),
                    "MATCH_ALREADY_IN_QUEUE", "已在匹配队列中", false, null);
            persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), false, reply);
            send(sessionId, reply);
            return;
        }

        persistenceService.recordMatchJoin(sessionId, message.msgId());
        OutboundMessage joined = OutboundMessage.success(MessageType.MATCH_JOINED, null, message.msgId(),
                new MatchJoinedPayload(sessionId, "QUEUED"));
        persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), true, joined);
        send(sessionId, joined);
        tryCreateRoom();
    }

    private void handleMatchCancel(String sessionId, InboundMessage message) {
        cancelMatchIfQueued(sessionId, true);
        send(sessionId, OutboundMessage.success(MessageType.MATCH_CANCELLED, null, message.msgId(), Map.of("status", "CANCELLED")));
    }

    private void handleMove(String sessionId, InboundMessage message) {
        RoomRuntime room = requireRoom(message.roomId(), sessionId);
        PersistenceScope scope = new PersistenceScope("ROOM", room.roomId, sessionId, message.msgId(), message.type());
        if (replayIfNeeded(scope, sessionId)) {
            return;
        }
        MoveRequestPayload payload = convert(message.payload(), MoveRequestPayload.class);
        synchronized (room) {
            ensurePlaying(room);
            if (room.pendingControl != null) {
                throw new IllegalMoveException("CONTROL_CONFLICT", "存在未处理的控制请求");
            }
            if (payload == null || payload.moveNo() == null || payload.from() == null || payload.to() == null || payload.piece() == null) {
                throw new IllegalArgumentException("走子参数缺失");
            }
            if (payload.moveNo() != room.moveNo + 1) {
                OutboundMessage reply = OutboundMessage.failure(MessageType.MOVE_REJECTED, room.roomId, message.msgId(),
                        "MOVE_NO_CONFLICT", "moveNo 与服务端不一致", false, moveRejectPayload(room));
                persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), false, reply);
                send(sessionId, reply);
                return;
            }
            Side mover = room.sideOf(sessionId);
            if (mover != room.currentTurn) {
                throw new IllegalMoveException("TURN_CONFLICT", "当前不是你的回合");
            }

            long now = Instant.now().toEpochMilli();
            int elapsed = (int) Math.max(0, now - room.lastMoveAtMs);
            deductTime(room, mover, elapsed);
            if (timeLeft(room, mover) <= 0) {
                finishGame(room, mover.opposite(), FinishReason.TIMEOUT);
                OutboundMessage reply = OutboundMessage.failure(MessageType.MOVE_REJECTED, room.roomId, message.msgId(),
                        "ROOM_FINISHED", "已超时结束", false, moveRejectPayload(room));
                persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), false, reply);
                send(sessionId, reply);
                return;
            }

            room.undoSnapshot = new MoveStateSnapshot(room.board.toFen(room.currentTurn), room.currentTurn, room.moveNo,
                    room.redTimeLeftMs, room.blackTimeLeftMs, now);
            AppliedMove appliedMove = xiangqiEngine.applyMove(room.board, mover, payload.from(), payload.to(), elapsed, payload.moveNo());
            room.moveNo = appliedMove.moveNo();
            addIncrement(room, mover);
            room.currentTurn = mover.opposite();
            room.lastMoveAtMs = now;
            room.moveHistory.add(appliedMove);
            persistenceService.saveMove(room, appliedMove, message.msgId());
            persistenceService.saveRoom(room);
            scheduleTurnTimeout(room);

            MoveAcceptedPayload acceptedPayload = new MoveAcceptedPayload(
                    room.moveNo, appliedMove.from(), appliedMove.to(), appliedMove.piece(), appliedMove.capturedPiece(),
                    room.board.toFen(room.currentTurn), room.currentTurn, room.redTimeLeftMs, room.blackTimeLeftMs
            );
            OutboundMessage reply = OutboundMessage.success(MessageType.MOVE_ACCEPTED, room.roomId, message.msgId(), acceptedPayload);
            persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), true, reply);
            broadcastRoom(room, reply);
            broadcastRoom(room, OutboundMessage.success(MessageType.TIME_SYNC, room.roomId, message.msgId(),
                    new TimeSyncPayload(room.moveNo, room.currentTurn, room.redTimeLeftMs, room.blackTimeLeftMs)));

            boolean opponentInCheck = xiangqiEngine.isInCheck(room.board, room.currentTurn);
            boolean opponentHasMove = xiangqiEngine.hasAnyLegalMove(room.board, room.currentTurn);
            if (!opponentHasMove) {
                finishGame(room, opponentInCheck ? mover : null, opponentInCheck ? FinishReason.CHECKMATE : FinishReason.DRAW_STALEMATE);
            }
        }
    }

    private void handleControlRequest(String sessionId, InboundMessage message, ControlType controlType) {
        RoomRuntime room = requireRoom(message.roomId(), sessionId);
        PersistenceScope scope = new PersistenceScope("ROOM", room.roomId, sessionId, message.msgId(), message.type());
        if (replayIfNeeded(scope, sessionId)) {
            return;
        }
        synchronized (room) {
            ensurePlaying(room);
            if (room.pendingControl != null) {
                OutboundMessage reply = OutboundMessage.failure(controlType == ControlType.UNDO ? MessageType.UNDO_REJECTED : MessageType.DRAW_REJECTED,
                        room.roomId, message.msgId(), "CONTROL_CONFLICT", "已有未决控制请求", false, null);
                persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), false, reply);
                send(sessionId, reply);
                return;
            }
            if (controlType == ControlType.UNDO && room.moveNo <= 0) {
                OutboundMessage reply = OutboundMessage.failure(MessageType.UNDO_REJECTED, room.roomId, message.msgId(),
                        "CONTROL_CONFLICT", "当前无可悔棋步", false, null);
                persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), false, reply);
                send(sessionId, reply);
                return;
            }

            String targetSessionId = room.opponentOf(sessionId);
            ControlEvent event = new ControlEvent(controlType, message.msgId(), sessionId, targetSessionId,
                    Instant.now().toEpochMilli() + CONTROL_TIMEOUT_MS);
            room.pendingControl = event;
            persistenceService.saveControlEvent(room.roomId,
                    controlType == ControlType.UNDO ? "UNDO_REQ" : "DRAW_REQ",
                    sessionId, targetSessionId, null, message.msgId(), room.moveNo, Map.of("expireAtMs", event.expireAtMs()));
            scheduleControlTimeout(room, event);

            MessageType pendingType = controlType == ControlType.UNDO ? MessageType.UNDO_PENDING : MessageType.DRAW_PENDING;
            PendingControlPayload payload = new PendingControlPayload(controlType, sessionId, event.expireAtMs());
            OutboundMessage reply = OutboundMessage.success(pendingType, room.roomId, message.msgId(), payload);
            persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), true, reply);
            send(sessionId, reply);
            send(targetSessionId, OutboundMessage.success(pendingType, room.roomId, message.msgId(), payload));
        }
    }

    private void handleControlResponse(String sessionId, InboundMessage message, ControlType controlType) {
        RoomRuntime room = requireRoom(message.roomId(), sessionId);
        PersistenceScope scope = new PersistenceScope("ROOM", room.roomId, sessionId, message.msgId(), message.type());
        if (replayIfNeeded(scope, sessionId)) {
            return;
        }
        ControlDecisionPayload payload = convert(message.payload(), ControlDecisionPayload.class);
        synchronized (room) {
            ensurePlaying(room);
            if (room.pendingControl == null || room.pendingControl.type() != controlType || !Objects.equals(room.pendingControl.targetSessionId(), sessionId)) {
                MessageType rejectedType = controlType == ControlType.UNDO ? MessageType.UNDO_REJECTED : MessageType.DRAW_REJECTED;
                OutboundMessage reply = OutboundMessage.failure(rejectedType, room.roomId, message.msgId(),
                        "CONTROL_CONFLICT", "不存在待处理控制请求", false, null);
                persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), false, reply);
                send(sessionId, reply);
                return;
            }
            cancelControlTimeout(room.roomId);
            ControlEvent current = room.pendingControl;
            room.pendingControl = null;
            boolean accept = payload != null && "ACCEPT".equalsIgnoreCase(payload.decision());
            persistenceService.saveControlEvent(room.roomId,
                    controlType == ControlType.UNDO ? "UNDO_RESP" : "DRAW_RESP",
                    sessionId, current.initiatorSessionId(), accept ? "ACCEPT" : "REJECT", message.msgId(), room.moveNo, Map.of());

            if (!accept) {
                MessageType rejectedType = controlType == ControlType.UNDO ? MessageType.UNDO_REJECTED : MessageType.DRAW_REJECTED;
                OutboundMessage rejected = OutboundMessage.success(rejectedType, room.roomId, message.msgId(), new ControlResultPayload("REJECT"));
                persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), true, rejected);
                send(sessionId, rejected);
                send(current.initiatorSessionId(), rejected);
                return;
            }
            if (controlType == ControlType.DRAW) {
                OutboundMessage reply = OutboundMessage.success(MessageType.DRAW_RESPONSE, room.roomId, message.msgId(), new ControlResultPayload("ACCEPT"));
                persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), true, reply);
                send(sessionId, reply);
                send(current.initiatorSessionId(), reply);
                finishGame(room, null, FinishReason.DRAW_AGREED);
                return;
            }
            if (room.undoSnapshot == null) {
                OutboundMessage reply = OutboundMessage.failure(MessageType.UNDO_REJECTED, room.roomId, message.msgId(),
                        "SYSTEM_ERROR", "缺少悔棋快照", false, null);
                persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), false, reply);
                send(sessionId, reply);
                return;
            }
            room.board = xiangqiEngine.fromFen(room.undoSnapshot.boardFen());
            room.currentTurn = room.undoSnapshot.currentTurn();
            room.moveNo = room.undoSnapshot.moveNo();
            room.redTimeLeftMs = room.undoSnapshot.redTimeLeftMs();
            room.blackTimeLeftMs = room.undoSnapshot.blackTimeLeftMs();
            room.lastMoveAtMs = Instant.now().toEpochMilli();
            if (!room.moveHistory.isEmpty()) {
                room.moveHistory.remove(room.moveHistory.size() - 1);
            }
            room.undoSnapshot = null;
            persistenceService.saveRoom(room);
            scheduleTurnTimeout(room);
            UndoAppliedPayload undoPayload = new UndoAppliedPayload(room.moveNo, room.board.toFen(room.currentTurn),
                    room.currentTurn, room.redTimeLeftMs, room.blackTimeLeftMs);
            OutboundMessage reply = OutboundMessage.success(MessageType.UNDO_APPLIED, room.roomId, message.msgId(), undoPayload);
            persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), true, reply);
            broadcastRoom(room, reply);
        }
    }

    private void handleResign(String sessionId, InboundMessage message) {
        RoomRuntime room = requireRoom(message.roomId(), sessionId);
        PersistenceScope scope = new PersistenceScope("ROOM", room.roomId, sessionId, message.msgId(), message.type());
        if (replayIfNeeded(scope, sessionId)) {
            return;
        }
        synchronized (room) {
            ensurePlaying(room);
            Side loser = room.sideOf(sessionId);
            OutboundMessage reply = OutboundMessage.success(MessageType.RESIGN, room.roomId, message.msgId(),
                    Map.of("status", "ACCEPTED"));
            persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), true, reply);
            send(sessionId, reply);
            finishGame(room, loser.opposite(), FinishReason.RESIGN);
        }
    }

    private void handleChat(String sessionId, InboundMessage message) {
        RoomRuntime room = requireRoom(message.roomId(), sessionId);
        PersistenceScope scope = new PersistenceScope("CHAT", room.roomId, sessionId, message.msgId(), message.type());
        if (replayIfNeeded(scope, sessionId)) {
            return;
        }
        ChatPayload payload = convert(message.payload(), ChatPayload.class);
        if (payload == null || payload.content() == null || payload.content().isBlank()) {
            OutboundMessage reply = OutboundMessage.failure(MessageType.CHAT_ACCEPTED, room.roomId, message.msgId(),
                    "INVALID_REQUEST", "聊天内容不能为空", false, null);
            persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), false, reply);
            send(sessionId, reply);
            return;
        }
        if (payload.content().length() > 300) {
            OutboundMessage reply = OutboundMessage.failure(MessageType.CHAT_ACCEPTED, room.roomId, message.msgId(),
                    "CHAT_TOO_LONG", "聊天内容超过 300 字符", false, null);
            persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), false, reply);
            send(sessionId, reply);
            return;
        }
        if (!allowChat(sessionId)) {
            OutboundMessage reply = OutboundMessage.failure(MessageType.CHAT_ACCEPTED, room.roomId, message.msgId(),
                    "CHAT_RATE_LIMITED", "发送过于频繁", true, null);
            persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), false, reply);
            send(sessionId, reply);
            return;
        }

        persistenceService.saveChat(room.roomId, sessionId, message.msgId(), payload.content(), true);
        OutboundMessage reply = OutboundMessage.success(MessageType.CHAT_ACCEPTED, room.roomId, message.msgId(), Map.of("status", "ACCEPTED"));
        persistenceService.saveIdempotent(scope.bizScope(), scope.roomId(), scope.sessionId(), scope.msgId(), scope.msgType(), true, reply);
        send(sessionId, reply);
        ChatBroadcastPayload broadcastPayload = new ChatBroadcastPayload(IdGenerator.chatMessageId(), sessionId, payload.content(), Instant.now().toEpochMilli());
        broadcastRoom(room, OutboundMessage.success(MessageType.CHAT_BROADCAST, room.roomId, message.msgId(), broadcastPayload));
    }

    private void handleSnapshot(String sessionId, InboundMessage message) {
        RoomRuntime room = requireRoom(message.roomId(), sessionId);
        synchronized (room) {
            PendingControlPayload pendingPayload = room.pendingControl == null ? null :
                    new PendingControlPayload(room.pendingControl.type(), room.pendingControl.initiatorSessionId(), room.pendingControl.expireAtMs());
            List<SnapshotMovePayload> recentMoves = room.moveHistory.stream()
                    .skip(Math.max(0, room.moveHistory.size() - 20L))
                    .map(move -> new SnapshotMovePayload(move.moveNo(), move.from(), move.to(), move.piece(), move.createdAtMs()))
                    .toList();
            List<ChatBroadcastPayload> recentChats = persistenceService.loadRecentChats(room.roomId, 20).stream()
                    .map(chat -> new ChatBroadcastPayload(IdGenerator.chatMessageId(), chat.senderSessionId(), chat.content(), chat.createdAtMs()))
                    .toList();
            SnapshotPayload payload = new SnapshotPayload(
                    new SnapshotRoomPayload(room.roomId, room.status, room.currentTurn, room.board.toFen(room.currentTurn),
                            room.moveNo, room.redTimeLeftMs, room.blackTimeLeftMs),
                    recentMoves,
                    pendingPayload,
                    recentChats
            );
            send(sessionId, OutboundMessage.success(MessageType.SNAPSHOT_SYNCED, room.roomId, message.msgId(), payload));
        }
    }

    private void tryCreateRoom() {
        String first;
        String second;
        synchronized (queueLock) {
            if (matchQueue.size() < 2) {
                return;
            }
            first = pollNextQueued();
            second = pollNextQueued();
        }
        if (first == null || second == null) {
            return;
        }

        String roomId = IdGenerator.roomId();
        boolean redFirst = Math.abs(roomId.hashCode()) % 2 == 0;
        String redSessionId = redFirst ? first : second;
        String blackSessionId = redFirst ? second : first;
        RoomRuntime room = new RoomRuntime(roomId, redSessionId, blackSessionId, BASE_TIME_MS, INCREMENT_MS, xiangqiEngine.initialBoard());
        rooms.put(roomId, room);
        sessionService.assignRoom(redSessionId, roomId);
        sessionService.assignRoom(blackSessionId, roomId);
        persistenceService.recordMatched(roomId, redSessionId, blackSessionId);
        persistenceService.saveRoom(room);
        scheduleTurnTimeout(room);

        send(redSessionId, OutboundMessage.success(MessageType.MATCH_SUCCESS, roomId, null,
                new MatchSuccessPayload(roomId, Side.RED, blackSessionId, BASE_TIME_MS, INCREMENT_MS, room.board.toFen(room.currentTurn))));
        send(blackSessionId, OutboundMessage.success(MessageType.MATCH_SUCCESS, roomId, null,
                new MatchSuccessPayload(roomId, Side.BLACK, redSessionId, BASE_TIME_MS, INCREMENT_MS, room.board.toFen(room.currentTurn))));
    }

    private String pollNextQueued() {
        while (!matchQueue.isEmpty()) {
            String sessionId = matchQueue.pollFirst();
            if (sessionId != null && queuedSessions.remove(sessionId)) {
                return sessionId;
            }
        }
        return null;
    }

    private void cancelMatchIfQueued(String sessionId, boolean persist) {
        boolean removed = queuedSessions.remove(sessionId);
        if (removed && persist) {
            persistenceService.recordMatchCancelled(sessionId);
        }
    }

    private boolean replayIfNeeded(PersistenceScope scope, String sessionId) {
        return persistenceService.loadIdempotent(scope.bizScope(), scope.sessionId(), scope.msgId())
                .map(stored -> {
                    try {
                        OutboundMessage reply = objectMapper.readValue(stored.responseJson(), OutboundMessage.class);
                        send(sessionId, reply);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                    return true;
                })
                .orElse(false);
    }

    private RoomRuntime requireRoom(String roomId, String sessionId) {
        if (roomId == null) {
            throw new IllegalArgumentException("roomId 不能为空");
        }
        RoomRuntime room = rooms.get(roomId);
        if (room == null) {
            throw new IllegalMoveException("ROOM_NOT_FOUND", "房间不存在");
        }
        if (!Objects.equals(room.redSessionId, sessionId) && !Objects.equals(room.blackSessionId, sessionId)) {
            throw new IllegalMoveException("UNAUTHORIZED", "当前会话不属于该房间");
        }
        return room;
    }

    private void ensurePlaying(RoomRuntime room) {
        if (room.status == RoomStatus.FINISHED) {
            throw new IllegalMoveException("ROOM_FINISHED", "房间已结束");
        }
    }

    private <T> T convert(Object payload, Class<T> type) {
        return payload == null ? null : objectMapper.convertValue(payload, type);
    }

    private void scheduleTurnTimeout(RoomRuntime room) {
        cancelTurnTimeout(room.roomId);
        if (room.status != RoomStatus.PLAYING) {
            return;
        }
        long delay = Math.max(1, timeLeft(room, room.currentTurn));
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            synchronized (room) {
                if (room.status != RoomStatus.PLAYING) {
                    return;
                }
                long now = Instant.now().toEpochMilli();
                int elapsed = (int) Math.max(0, now - room.lastMoveAtMs);
                deductTime(room, room.currentTurn, elapsed);
                if (timeLeft(room, room.currentTurn) <= 0) {
                    finishGame(room, room.currentTurn.opposite(), FinishReason.TIMEOUT);
                } else {
                    room.lastMoveAtMs = now;
                    scheduleTurnTimeout(room);
                }
            }
        }, delay, TimeUnit.MILLISECONDS);
        roomTimeouts.put(room.roomId, future);
    }

    private void cancelTurnTimeout(String roomId) {
        ScheduledFuture<?> future = roomTimeouts.remove(roomId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void scheduleReconnectTimeout(String sessionId, RoomRuntime room) {
        cancelReconnectTimeout(sessionId);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            synchronized (room) {
                if (room.status != RoomStatus.PLAYING || sessionService.isOnline(sessionId)) {
                    return;
                }
                Side loser = room.sideOf(sessionId);
                finishGame(room, loser.opposite(), FinishReason.RECONNECT_TIMEOUT);
            }
        }, RECONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        reconnectTimeouts.put(sessionId, future);
    }

    private void cancelReconnectTimeout(String sessionId) {
        ScheduledFuture<?> future = reconnectTimeouts.remove(sessionId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void scheduleControlTimeout(RoomRuntime room, ControlEvent event) {
        cancelControlTimeout(room.roomId);
        ScheduledFuture<?> future = scheduler.schedule(() -> {
            synchronized (room) {
                if (room.pendingControl == null || !Objects.equals(room.pendingControl.msgId(), event.msgId())) {
                    return;
                }
                room.pendingControl = null;
                MessageType rejectedType = event.type() == ControlType.UNDO ? MessageType.UNDO_REJECTED : MessageType.DRAW_REJECTED;
                persistenceService.saveControlEvent(room.roomId,
                        event.type() == ControlType.UNDO ? "UNDO_RESP" : "DRAW_RESP",
                        event.targetSessionId(), event.initiatorSessionId(), "TIMEOUT", event.msgId(), room.moveNo, Map.of());
                OutboundMessage timeoutMessage = OutboundMessage.success(rejectedType, room.roomId, event.msgId(), new ControlResultPayload("TIMEOUT"));
                send(event.initiatorSessionId(), timeoutMessage);
                send(event.targetSessionId(), timeoutMessage);
            }
        }, CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        controlTimeouts.put(room.roomId, future);
    }

    private void cancelControlTimeout(String roomId) {
        ScheduledFuture<?> future = controlTimeouts.remove(roomId);
        if (future != null) {
            future.cancel(false);
        }
    }

    private void finishGame(RoomRuntime room, Side winnerSide, FinishReason reason) {
        room.status = RoomStatus.FINISHED;
        room.pendingControl = null;
        room.disconnectDeadlineMs = 0L;
        cancelTurnTimeout(room.roomId);
        cancelReconnectTimeout(room.redSessionId);
        cancelReconnectTimeout(room.blackSessionId);
        cancelControlTimeout(room.roomId);
        sessionService.clearRoom(room.redSessionId, room.roomId);
        sessionService.clearRoom(room.blackSessionId, room.roomId);
        persistenceService.saveRoom(room);
        persistenceService.saveResult(room.roomId, winnerSide, reason, room.moveNo,
                (int) Math.max(0, Instant.now().toEpochMilli() - room.createdAtMs), room.board.toFen(room.currentTurn));
        GameOverPayload payload = new GameOverPayload(winnerSide, reason, room.moveNo, room.board.toFen(room.currentTurn), Instant.now().toEpochMilli());
        broadcastRoom(room, OutboundMessage.success(MessageType.GAME_OVER, room.roomId, null, payload));
    }

    private void broadcastRoom(RoomRuntime room, OutboundMessage message) {
        send(room.redSessionId, message);
        if (!Objects.equals(room.redSessionId, room.blackSessionId)) {
            send(room.blackSessionId, message);
        }
    }

    private void send(String sessionId, OutboundMessage message) {
        try {
            sessionService.push(sessionId, objectMapper.writeValueAsString(message));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private boolean allowChat(String sessionId) {
        long now = Instant.now().toEpochMilli();
        Deque<Long> window = chatWindows.computeIfAbsent(sessionId, ignored -> new ArrayDeque<>());
        synchronized (window) {
            while (!window.isEmpty() && now - window.peekFirst() > 5_000L) {
                window.pollFirst();
            }
            if (window.size() >= 5) {
                return false;
            }
            window.offerLast(now);
            return true;
        }
    }

    private int timeLeft(RoomRuntime room, Side side) {
        return side == Side.RED ? room.redTimeLeftMs : room.blackTimeLeftMs;
    }

    private void deductTime(RoomRuntime room, Side side, int elapsed) {
        if (side == Side.RED) {
            room.redTimeLeftMs = Math.max(0, room.redTimeLeftMs - elapsed);
        } else {
            room.blackTimeLeftMs = Math.max(0, room.blackTimeLeftMs - elapsed);
        }
    }

    private void addIncrement(RoomRuntime room, Side side) {
        if (side == Side.RED) {
            room.redTimeLeftMs += room.incrementMs;
        } else {
            room.blackTimeLeftMs += room.incrementMs;
        }
    }

    private String authoritativeFen(String roomId) {
        RoomRuntime room = roomId == null ? null : rooms.get(roomId);
        return room == null ? null : room.board.toFen(room.currentTurn);
    }

    private Map<String, Object> moveRejectPayload(String roomId) {
        Map<String, Object> payload = new ConcurrentHashMap<>();
        String authoritativeFen = authoritativeFen(roomId);
        if (authoritativeFen != null) {
            payload.put("authoritativeFen", authoritativeFen);
        }
        int expectedMoveNo = expectedMoveNo(roomId);
        if (expectedMoveNo > 0) {
            payload.put("expectedMoveNo", expectedMoveNo);
        }
        return payload;
    }

    private Map<String, Object> moveRejectPayload(RoomRuntime room) {
        return Map.of("authoritativeFen", room.board.toFen(room.currentTurn), "expectedMoveNo", room.moveNo + 1);
    }

    private int expectedMoveNo(String roomId) {
        RoomRuntime room = roomId == null ? null : rooms.get(roomId);
        return room == null ? 0 : room.moveNo + 1;
    }

    private MessageType resolveErrorType(MessageType originalType) {
        return switch (originalType) {
            case MATCH_JOIN, MATCH_CANCEL -> MessageType.MATCH_CANCELLED;
            case MOVE_REQUEST -> MessageType.MOVE_REJECTED;
            case UNDO_REQUEST, UNDO_RESPONSE -> MessageType.UNDO_REJECTED;
            case DRAW_REQUEST, DRAW_RESPONSE -> MessageType.DRAW_REJECTED;
            case CHAT_SEND -> MessageType.CHAT_ACCEPTED;
            case SNAPSHOT_SYNC -> MessageType.SNAPSHOT_SYNCED;
            case PING -> MessageType.PONG;
            default -> originalType;
        };
    }

    private record PersistenceScope(
            String bizScope,
            String roomId,
            String sessionId,
            String msgId,
            MessageType msgType
    ) {
    }
}
