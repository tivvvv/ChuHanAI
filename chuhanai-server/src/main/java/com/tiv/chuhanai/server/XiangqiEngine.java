package com.tiv.chuhanai.server;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

@Component
class XiangqiEngine {

    BoardState initialBoard() {
        BoardState board = new BoardState();
        board.place("a0", Piece.red(PieceKind.ROOK));
        board.place("b0", Piece.red(PieceKind.KNIGHT));
        board.place("c0", Piece.red(PieceKind.ELEPHANT));
        board.place("d0", Piece.red(PieceKind.ADVISOR));
        board.place("e0", Piece.red(PieceKind.KING));
        board.place("f0", Piece.red(PieceKind.ADVISOR));
        board.place("g0", Piece.red(PieceKind.ELEPHANT));
        board.place("h0", Piece.red(PieceKind.KNIGHT));
        board.place("i0", Piece.red(PieceKind.ROOK));
        board.place("b2", Piece.red(PieceKind.CANNON));
        board.place("h2", Piece.red(PieceKind.CANNON));
        board.place("a3", Piece.red(PieceKind.PAWN));
        board.place("c3", Piece.red(PieceKind.PAWN));
        board.place("e3", Piece.red(PieceKind.PAWN));
        board.place("g3", Piece.red(PieceKind.PAWN));
        board.place("i3", Piece.red(PieceKind.PAWN));

        board.place("a9", Piece.black(PieceKind.ROOK));
        board.place("b9", Piece.black(PieceKind.KNIGHT));
        board.place("c9", Piece.black(PieceKind.ELEPHANT));
        board.place("d9", Piece.black(PieceKind.ADVISOR));
        board.place("e9", Piece.black(PieceKind.KING));
        board.place("f9", Piece.black(PieceKind.ADVISOR));
        board.place("g9", Piece.black(PieceKind.ELEPHANT));
        board.place("h9", Piece.black(PieceKind.KNIGHT));
        board.place("i9", Piece.black(PieceKind.ROOK));
        board.place("b7", Piece.black(PieceKind.CANNON));
        board.place("h7", Piece.black(PieceKind.CANNON));
        board.place("a6", Piece.black(PieceKind.PAWN));
        board.place("c6", Piece.black(PieceKind.PAWN));
        board.place("e6", Piece.black(PieceKind.PAWN));
        board.place("g6", Piece.black(PieceKind.PAWN));
        board.place("i6", Piece.black(PieceKind.PAWN));
        return board;
    }

    AppliedMove applyMove(BoardState board, Side side, String fromText, String toText, long thinkTimeMs, int moveNo) {
        Position from = Position.parse(fromText);
        Position to = Position.parse(toText);
        Piece moving = board.get(from);
        if (moving == null) {
            throw new IllegalMoveException("MOVE_ILLEGAL", "起点无棋子");
        }
        if (moving.side() != side) {
            throw new IllegalMoveException("TURN_CONFLICT", "不能操作对方棋子");
        }
        Piece target = board.get(to);
        if (target != null && target.side() == side) {
            throw new IllegalMoveException("MOVE_ILLEGAL", "不能吃己方棋子");
        }
        if (!isPseudoLegal(board, moving, from, to)) {
            throw new IllegalMoveException("MOVE_ILLEGAL", moving.kind().display() + "走法不合法");
        }

        BoardState simulated = board.copy();
        Piece captured = simulated.move(from, to);
        if (kingsFacing(simulated) || isInCheck(simulated, side)) {
            throw new IllegalMoveException("MOVE_ILLEGAL", "该走法会导致本方将帅受威胁");
        }

        String beforeFen = board.toFen(side);
        board.move(from, to);
        String afterFen = board.toFen(side.opposite());
        return new AppliedMove(moveNo, side, moving.kind().name(), fromText, toText,
                captured == null ? null : captured.kind().name(), beforeFen, afterFen, (int) thinkTimeMs, Instant.now().toEpochMilli());
    }

    boolean isInCheck(BoardState board, Side side) {
        Position king = board.findKing(side);
        if (king == null) {
            return true;
        }
        for (Position from : board.positions()) {
            Piece piece = board.get(from);
            if (piece == null || piece.side() == side) {
                continue;
            }
            if (isPseudoLegal(board, piece, from, king)) {
                return true;
            }
        }
        return false;
    }

    boolean hasAnyLegalMove(BoardState board, Side side) {
        for (Position from : board.positions()) {
            Piece piece = board.get(from);
            if (piece == null || piece.side() != side) {
                continue;
            }
            for (Position to : board.positions()) {
                Piece target = board.get(to);
                if (target != null && target.side() == side) {
                    continue;
                }
                if (!isPseudoLegal(board, piece, from, to)) {
                    continue;
                }
                BoardState simulated = board.copy();
                simulated.move(from, to);
                if (!kingsFacing(simulated) && !isInCheck(simulated, side)) {
                    return true;
                }
            }
        }
        return false;
    }

    BoardState fromFen(String fen) {
        return BoardState.fromFen(fen);
    }

    private boolean isPseudoLegal(BoardState board, Piece moving, Position from, Position to) {
        if (from.equals(to)) {
            return false;
        }
        return switch (moving.kind()) {
            case ROOK -> rookLegal(board, from, to);
            case KNIGHT -> knightLegal(board, from, to);
            case CANNON -> cannonLegal(board, from, to);
            case KING -> kingLegal(board, moving.side(), from, to);
            case ADVISOR -> advisorLegal(moving.side(), from, to);
            case ELEPHANT -> elephantLegal(board, moving.side(), from, to);
            case PAWN -> pawnLegal(moving.side(), from, to);
        };
    }

    private boolean rookLegal(BoardState board, Position from, Position to) {
        return sameLine(from, to) && countBetween(board, from, to) == 0;
    }

    private boolean knightLegal(BoardState board, Position from, Position to) {
        int dx = to.x() - from.x();
        int dy = to.y() - from.y();
        int absDx = Math.abs(dx);
        int absDy = Math.abs(dy);
        if (!((absDx == 1 && absDy == 2) || (absDx == 2 && absDy == 1))) {
            return false;
        }
        Position leg = absDx == 2 ? new Position(from.x() + dx / 2, from.y()) : new Position(from.x(), from.y() + dy / 2);
        return board.get(leg) == null;
    }

    private boolean cannonLegal(BoardState board, Position from, Position to) {
        if (!sameLine(from, to)) {
            return false;
        }
        int between = countBetween(board, from, to);
        Piece target = board.get(to);
        if (target == null) {
            return between == 0;
        }
        return between == 1;
    }

    private boolean kingLegal(BoardState board, Side side, Position from, Position to) {
        Piece target = board.get(to);
        if (target != null && target.kind() == PieceKind.KING && from.x() == to.x()) {
            return countBetween(board, from, to) == 0;
        }
        int dx = Math.abs(to.x() - from.x());
        int dy = Math.abs(to.y() - from.y());
        return dx + dy == 1 && inPalace(side, to);
    }

    private boolean advisorLegal(Side side, Position from, Position to) {
        return Math.abs(to.x() - from.x()) == 1 && Math.abs(to.y() - from.y()) == 1 && inPalace(side, to);
    }

    private boolean elephantLegal(BoardState board, Side side, Position from, Position to) {
        int dx = Math.abs(to.x() - from.x());
        int dy = Math.abs(to.y() - from.y());
        if (dx != 2 || dy != 2) {
            return false;
        }
        if (side == Side.RED && to.y() > 4) {
            return false;
        }
        if (side == Side.BLACK && to.y() < 5) {
            return false;
        }
        Position eye = new Position((from.x() + to.x()) / 2, (from.y() + to.y()) / 2);
        return board.get(eye) == null;
    }

    private boolean pawnLegal(Side side, Position from, Position to) {
        int dx = to.x() - from.x();
        int dy = to.y() - from.y();
        int forward = side == Side.RED ? 1 : -1;
        boolean crossedRiver = side == Side.RED ? from.y() >= 5 : from.y() <= 4;
        if (dy == forward && dx == 0) {
            return true;
        }
        return crossedRiver && dy == 0 && Math.abs(dx) == 1;
    }

    private boolean kingsFacing(BoardState board) {
        Position redKing = board.findKing(Side.RED);
        Position blackKing = board.findKing(Side.BLACK);
        if (redKing == null || blackKing == null || redKing.x() != blackKing.x()) {
            return false;
        }
        return countBetween(board, redKing, blackKing) == 0;
    }

    private int countBetween(BoardState board, Position from, Position to) {
        int count = 0;
        if (from.x() == to.x()) {
            int start = Math.min(from.y(), to.y()) + 1;
            int end = Math.max(from.y(), to.y());
            for (int y = start; y < end; y++) {
                if (board.get(new Position(from.x(), y)) != null) {
                    count++;
                }
            }
            return count;
        }
        if (from.y() == to.y()) {
            int start = Math.min(from.x(), to.x()) + 1;
            int end = Math.max(from.x(), to.x());
            for (int x = start; x < end; x++) {
                if (board.get(new Position(x, from.y())) != null) {
                    count++;
                }
            }
            return count;
        }
        return Integer.MAX_VALUE;
    }

    private boolean sameLine(Position from, Position to) {
        return from.x() == to.x() || from.y() == to.y();
    }

    private boolean inPalace(Side side, Position to) {
        boolean xInPalace = to.x() >= 3 && to.x() <= 5;
        if (!xInPalace) {
            return false;
        }
        return side == Side.RED ? to.y() >= 0 && to.y() <= 2 : to.y() >= 7 && to.y() <= 9;
    }
}

record AppliedMove(
        int moveNo,
        Side side,
        String piece,
        String from,
        String to,
        String capturedPiece,
        String boardFenBefore,
        String boardFenAfter,
        int thinkTimeMs,
        long createdAtMs
) {
}

record MoveStateSnapshot(
        String boardFen,
        Side currentTurn,
        int moveNo,
        int redTimeLeftMs,
        int blackTimeLeftMs,
        long lastMoveAtMs
) {
}

enum PieceKind {
    KING("KING", 'K', 'k'),
    ADVISOR("ADVISOR", 'A', 'a'),
    ELEPHANT("ELEPHANT", 'E', 'e'),
    KNIGHT("KNIGHT", 'H', 'h'),
    ROOK("ROOK", 'R', 'r'),
    CANNON("CANNON", 'C', 'c'),
    PAWN("PAWN", 'P', 'p');

    private final String display;
    private final char redCode;
    private final char blackCode;

    PieceKind(String display, char redCode, char blackCode) {
        this.display = display;
        this.redCode = redCode;
        this.blackCode = blackCode;
    }

    String display() {
        return display;
    }

    char codeFor(Side side) {
        return side == Side.RED ? redCode : blackCode;
    }

    static PieceKind fromCode(char code) {
        for (PieceKind kind : values()) {
            if (kind.redCode == code || kind.blackCode == code) {
                return kind;
            }
        }
        throw new IllegalArgumentException("未知棋子编码: " + code);
    }
}

record Piece(
        Side side,
        PieceKind kind
) {
    static Piece red(PieceKind kind) {
        return new Piece(Side.RED, kind);
    }

    static Piece black(PieceKind kind) {
        return new Piece(Side.BLACK, kind);
    }
}

record Position(
        int x,
        int y
) {
    static Position parse(String text) {
        if (text == null || text.length() != 2) {
            throw new IllegalMoveException("INVALID_REQUEST", "坐标格式非法");
        }
        int x = text.charAt(0) - 'a';
        int y = text.charAt(1) - '0';
        Position position = new Position(x, y);
        if (!position.inBounds()) {
            throw new IllegalMoveException("INVALID_REQUEST", "坐标越界");
        }
        return position;
    }

    boolean inBounds() {
        return x >= 0 && x < 9 && y >= 0 && y < 10;
    }

    @Override
    public String toString() {
        return String.valueOf((char) ('a' + x)) + y;
    }
}

final class BoardState {
    private final Piece[][] board;

    BoardState() {
        this.board = new Piece[10][9];
    }

    private BoardState(Piece[][] board) {
        this.board = board;
    }

    Piece get(Position position) {
        return board[position.y()][position.x()];
    }

    void place(String position, Piece piece) {
        Position pos = Position.parse(position);
        board[pos.y()][pos.x()] = piece;
    }

    Piece move(Position from, Position to) {
        Piece moving = get(from);
        Piece captured = get(to);
        board[to.y()][to.x()] = moving;
        board[from.y()][from.x()] = null;
        return captured;
    }

    Position findKing(Side side) {
        for (Position position : positions()) {
            Piece piece = get(position);
            if (piece != null && piece.side() == side && piece.kind() == PieceKind.KING) {
                return position;
            }
        }
        return null;
    }

    List<Position> positions() {
        List<Position> positions = new ArrayList<>(90);
        for (int y = 0; y < 10; y++) {
            for (int x = 0; x < 9; x++) {
                positions.add(new Position(x, y));
            }
        }
        return positions;
    }

    BoardState copy() {
        Piece[][] copied = new Piece[10][9];
        for (int y = 0; y < 10; y++) {
            copied[y] = Arrays.copyOf(board[y], board[y].length);
        }
        return new BoardState(copied);
    }

    String toFen(Side currentTurn) {
        StringBuilder builder = new StringBuilder();
        for (int y = 9; y >= 0; y--) {
            int empty = 0;
            for (int x = 0; x < 9; x++) {
                Piece piece = board[y][x];
                if (piece == null) {
                    empty++;
                    continue;
                }
                if (empty > 0) {
                    builder.append(empty);
                    empty = 0;
                }
                builder.append(piece.kind().codeFor(piece.side()));
            }
            if (empty > 0) {
                builder.append(empty);
            }
            if (y > 0) {
                builder.append('/');
            }
        }
        builder.append(' ').append(currentTurn == Side.RED ? 'w' : 'b');
        return builder.toString();
    }

    static BoardState fromFen(String fen) {
        String[] segments = fen.split(" ");
        String[] rows = segments[0].split("/");
        if (rows.length != 10) {
            throw new IllegalArgumentException("无效 FEN");
        }
        BoardState board = new BoardState();
        for (int inputRow = 0; inputRow < rows.length; inputRow++) {
            String row = rows[inputRow];
            int y = 9 - inputRow;
            int x = 0;
            for (char ch : row.toCharArray()) {
                if (Character.isDigit(ch)) {
                    x += ch - '0';
                    continue;
                }
                PieceKind kind = PieceKind.fromCode(ch);
                Side side = Character.isUpperCase(ch) ? Side.RED : Side.BLACK;
                board.board[y][x++] = new Piece(side, kind);
            }
        }
        return board;
    }
}

class IllegalMoveException extends RuntimeException {
    private final String code;

    IllegalMoveException(String code, String message) {
        super(message);
        this.code = code;
    }

    String code() {
        return code;
    }
}

final class RoomRuntime {
    final String roomId;
    final String redSessionId;
    final String blackSessionId;
    final int baseTimeMs;
    final int incrementMs;
    final long createdAtMs;
    final List<AppliedMove> moveHistory = new ArrayList<>();
    RoomStatus status = RoomStatus.PLAYING;
    BoardState board;
    Side currentTurn = Side.RED;
    int moveNo = 0;
    int redTimeLeftMs;
    int blackTimeLeftMs;
    long lastMoveAtMs;
    long disconnectDeadlineMs;
    ControlEvent pendingControl;
    MoveStateSnapshot undoSnapshot;

    RoomRuntime(String roomId, String redSessionId, String blackSessionId, int baseTimeMs, int incrementMs, BoardState board) {
        this.roomId = roomId;
        this.redSessionId = redSessionId;
        this.blackSessionId = blackSessionId;
        this.baseTimeMs = baseTimeMs;
        this.incrementMs = incrementMs;
        this.board = board;
        this.redTimeLeftMs = baseTimeMs;
        this.blackTimeLeftMs = baseTimeMs;
        this.createdAtMs = Instant.now().toEpochMilli();
        this.lastMoveAtMs = this.createdAtMs;
    }

    Side sideOf(String sessionId) {
        if (Objects.equals(redSessionId, sessionId)) {
            return Side.RED;
        }
        if (Objects.equals(blackSessionId, sessionId)) {
            return Side.BLACK;
        }
        throw new IllegalArgumentException("Session not in room");
    }

    String opponentOf(String sessionId) {
        return Objects.equals(redSessionId, sessionId) ? blackSessionId : redSessionId;
    }
}
