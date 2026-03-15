package com.tiv.chuhanai.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
class PersistenceService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    PersistenceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    void recordMatchJoin(String sessionId, String msgId) {
        Timestamp now = now();
        jdbcTemplate.update("""
                insert into match_queue(session_id, enqueue_msg_id, status, enqueue_at, dequeue_at, matched_room_id)
                values (?, ?, 0, ?, null, null)
                on duplicate key update status = values(status), enqueue_at = values(enqueue_at), dequeue_at = null, matched_room_id = null
                """, sessionId, msgId, now);
    }

    void recordMatchCancelled(String sessionId) {
        jdbcTemplate.update("""
                update match_queue
                set status = 2, dequeue_at = ?
                where session_id = ? and status = 0
                """, now(), sessionId);
    }

    void recordMatched(String roomId, String redSessionId, String blackSessionId) {
        Timestamp now = now();
        jdbcTemplate.update("""
                update match_queue
                set status = 1, dequeue_at = ?, matched_room_id = ?
                where session_id in (?, ?) and status = 0
                """, now, roomId, redSessionId, blackSessionId);
    }

    void saveRoom(RoomRuntime room) {
        jdbcTemplate.update("""
                insert into game_room(room_id, status, red_session_id, black_session_id, current_turn, board_fen, move_no,
                red_time_left_ms, black_time_left_ms, base_time_ms, increment_ms, last_move_at, version, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
                on duplicate key update status = values(status), current_turn = values(current_turn), board_fen = values(board_fen),
                move_no = values(move_no), red_time_left_ms = values(red_time_left_ms), black_time_left_ms = values(black_time_left_ms),
                last_move_at = values(last_move_at), updated_at = values(updated_at), version = version + 1
                """,
                room.roomId,
                room.status.name(),
                room.redSessionId,
                room.blackSessionId,
                room.currentTurn.name(),
                room.board.toFen(room.currentTurn),
                room.moveNo,
                room.redTimeLeftMs,
                room.blackTimeLeftMs,
                room.baseTimeMs,
                room.incrementMs,
                room.lastMoveAtMs == 0 ? null : Timestamp.from(Instant.ofEpochMilli(room.lastMoveAtMs)),
                now(),
                now());
    }

    Optional<IdempotentResult> loadIdempotent(String bizScope, String sessionId, String msgId) {
        List<IdempotentResult> results = jdbcTemplate.query("""
                select msg_type, process_status, response_json
                from idempotent_message
                where biz_scope = ? and session_id = ? and msg_id = ?
                """,
                (rs, rowNum) -> new IdempotentResult(
                        MessageType.valueOf(rs.getString("msg_type")),
                        rs.getInt("process_status") == 1,
                        rs.getString("response_json")
                ),
                bizScope, sessionId, msgId);
        return results.stream().findFirst();
    }

    void saveIdempotent(String bizScope, String roomId, String sessionId, String msgId,
                        MessageType msgType, boolean success, OutboundMessage response) {
        jdbcTemplate.update("""
                insert into idempotent_message(biz_scope, room_id, session_id, msg_id, msg_type, process_status, response_json, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, cast(? as json), ?, ?)
                on duplicate key update process_status = values(process_status), response_json = values(response_json), updated_at = values(updated_at)
                """,
                bizScope, roomId, sessionId, msgId, msgType.name(), success ? 1 : 2, writeJson(response), now(), now());
    }

    void saveMove(RoomRuntime room, AppliedMove move, String requestMsgId) {
        jdbcTemplate.update("""
                insert into game_move(room_id, move_no, side, piece, from_pos, to_pos, think_time_ms,
                board_fen_before, board_fen_after, captured_piece, request_msg_id, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                room.roomId,
                move.moveNo(),
                move.side().name(),
                move.piece(),
                move.from(),
                move.to(),
                move.thinkTimeMs(),
                move.boardFenBefore(),
                move.boardFenAfter(),
                move.capturedPiece(),
                requestMsgId,
                Timestamp.from(Instant.ofEpochMilli(move.createdAtMs())));
    }

    void saveControlEvent(String roomId, String type, String initiatorSessionId, String targetSessionId,
                          String decision, String requestMsgId, Integer relatedMoveNo, Map<String, Object> extra) {
        jdbcTemplate.update("""
                insert into game_control_event(room_id, event_type, initiator_session_id, target_session_id,
                decision, request_msg_id, related_move_no, extra_json, created_at)
                values (?, ?, ?, ?, ?, ?, ?, cast(? as json), ?)
                on duplicate key update decision = values(decision), extra_json = values(extra_json)
                """,
                roomId, type, initiatorSessionId, targetSessionId, decision, requestMsgId, relatedMoveNo, writeJson(extra), now());
    }

    void saveResult(String roomId, Side winnerSide, FinishReason finishReason, int totalMoveCount, int durationMs, String finalFen) {
        jdbcTemplate.update("""
                insert into game_result(room_id, winner_side, finish_reason, total_move_count, duration_ms, final_fen, finished_at, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                on duplicate key update winner_side = values(winner_side), finish_reason = values(finish_reason),
                total_move_count = values(total_move_count), duration_ms = values(duration_ms), final_fen = values(final_fen), finished_at = values(finished_at)
                """,
                roomId, winnerSide == null ? "DRAW" : winnerSide.name(), finishReason.name(), totalMoveCount, durationMs, finalFen, now(), now());
    }

    void saveChat(String roomId, String senderSessionId, String requestMsgId, String content, boolean safe) {
        jdbcTemplate.update("""
                insert into chat_message(room_id, sender_session_id, content, content_safe, request_msg_id, created_at)
                values (?, ?, ?, ?, ?, ?)
                on duplicate key update content = values(content)
                """,
                roomId, senderSessionId, content, safe ? 1 : 0, requestMsgId, now());
    }

    List<MoveRecordView> loadRecentMoves(String roomId, int limit) {
        return jdbcTemplate.query("""
                select move_no, from_pos, to_pos, piece, created_at
                from game_move
                where room_id = ?
                order by move_no desc
                limit ?
                """, new MoveRecordMapper(), roomId, limit).reversed();
    }

    List<ChatRecordView> loadRecentChats(String roomId, int limit) {
        return jdbcTemplate.query("""
                select sender_session_id, content, created_at
                from chat_message
                where room_id = ?
                order by created_at desc
                limit ?
                """, new ChatRecordMapper(), roomId, limit).reversed();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private Timestamp now() {
        return Timestamp.from(Instant.now());
    }

    private static final class MoveRecordMapper implements RowMapper<MoveRecordView> {
        @Override
        public MoveRecordView mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MoveRecordView(
                    rs.getInt("move_no"),
                    rs.getString("from_pos"),
                    rs.getString("to_pos"),
                    rs.getString("piece"),
                    rs.getTimestamp("created_at").getTime()
            );
        }
    }

    private static final class ChatRecordMapper implements RowMapper<ChatRecordView> {
        @Override
        public ChatRecordView mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ChatRecordView(
                    rs.getString("sender_session_id"),
                    rs.getString("content"),
                    rs.getTimestamp("created_at").getTime()
            );
        }
    }
}
