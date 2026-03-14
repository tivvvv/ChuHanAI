package com.tiv.chuhanai.client.xiangqi;

import com.tiv.chuhanai.client.net.ClientProtocol.Side;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ClientXiangqiSupport {
    private ClientXiangqiSupport() {
    }

    public static List<Position> legalTargets(BoardState board, Side side, Position from) {
        Piece moving = board.get(from);
        if (moving == null || moving.side() != side) {
            return List.of();
        }
        List<Position> targets = new ArrayList<>();
        for (Position to : board.positions()) {
            Piece target = board.get(to);
            if (target != null && target.side() == side) {
                continue;
            }
            if (!isPseudoLegal(board, moving, from, to)) {
                continue;
            }
            BoardState simulated = board.copy();
            simulated.move(from, to);
            if (!kingsFacing(simulated) && !isInCheck(simulated, side)) {
                targets.add(to);
            }
        }
        return targets;
    }

    public static boolean isInCheck(BoardState board, Side side) {
        Position king = board.findKing(side);
        if (king == null) {
            return true;
        }
        for (Position pos : board.positions()) {
            Piece piece = board.get(pos);
            if (piece == null || piece.side() == side) {
                continue;
            }
            if (isPseudoLegal(board, piece, pos, king)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPseudoLegal(BoardState board, Piece moving, Position from, Position to) {
        if (from.equals(to)) {
            return false;
        }
        return switch (moving.kind()) {
            case ROOK -> sameLine(from, to) && countBetween(board, from, to) == 0;
            case KNIGHT -> knightLegal(board, from, to);
            case CANNON -> cannonLegal(board, from, to);
            case KING -> kingLegal(board, from, to);
            case ADVISOR -> Math.abs(to.x() - from.x()) == 1 && Math.abs(to.y() - from.y()) == 1 && inPalace(moving.side(), to);
            case ELEPHANT -> elephantLegal(board, moving.side(), from, to);
            case PAWN -> pawnLegal(moving.side(), from, to);
        };
    }

    private static boolean knightLegal(BoardState board, Position from, Position to) {
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

    private static boolean cannonLegal(BoardState board, Position from, Position to) {
        if (!sameLine(from, to)) {
            return false;
        }
        int between = countBetween(board, from, to);
        Piece target = board.get(to);
        return target == null ? between == 0 : between == 1;
    }

    private static boolean kingLegal(BoardState board, Position from, Position to) {
        Piece target = board.get(to);
        if (target != null && target.kind() == PieceKind.KING && from.x() == to.x()) {
            return countBetween(board, from, to) == 0;
        }
        return Math.abs(to.x() - from.x()) + Math.abs(to.y() - from.y()) == 1 && inPalace(board.get(from).side(), to);
    }

    private static boolean elephantLegal(BoardState board, Side side, Position from, Position to) {
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

    private static boolean pawnLegal(Side side, Position from, Position to) {
        int dx = to.x() - from.x();
        int dy = to.y() - from.y();
        int forward = side == Side.RED ? 1 : -1;
        boolean crossedRiver = side == Side.RED ? from.y() >= 5 : from.y() <= 4;
        if (dy == forward && dx == 0) {
            return true;
        }
        return crossedRiver && dy == 0 && Math.abs(dx) == 1;
    }

    private static boolean kingsFacing(BoardState board) {
        Position redKing = board.findKing(Side.RED);
        Position blackKing = board.findKing(Side.BLACK);
        return redKing != null && blackKing != null && redKing.x() == blackKing.x() && countBetween(board, redKing, blackKing) == 0;
    }

    private static int countBetween(BoardState board, Position from, Position to) {
        int count = 0;
        if (from.x() == to.x()) {
            for (int y = Math.min(from.y(), to.y()) + 1; y < Math.max(from.y(), to.y()); y++) {
                if (board.get(new Position(from.x(), y)) != null) {
                    count++;
                }
            }
            return count;
        }
        if (from.y() == to.y()) {
            for (int x = Math.min(from.x(), to.x()) + 1; x < Math.max(from.x(), to.x()); x++) {
                if (board.get(new Position(x, from.y())) != null) {
                    count++;
                }
            }
            return count;
        }
        return Integer.MAX_VALUE;
    }

    private static boolean sameLine(Position from, Position to) {
        return from.x() == to.x() || from.y() == to.y();
    }

    private static boolean inPalace(Side side, Position to) {
        if (to.x() < 3 || to.x() > 5) {
            return false;
        }
        return side == Side.RED ? to.y() >= 0 && to.y() <= 2 : to.y() >= 7 && to.y() <= 9;
    }

    public record Position(int x, int y) {
        public static Position parse(String text) {
            if (text == null || text.length() != 2) {
                throw new IllegalArgumentException("Invalid board coordinate");
            }
            int x = text.charAt(0) - 'a';
            int y = text.charAt(1) - '0';
            return new Position(x, y);
        }

        public boolean inBounds() {
            return x >= 0 && x < 9 && y >= 0 && y < 10;
        }

        @Override
        public String toString() {
            return String.valueOf((char) ('a' + x)) + y;
        }
    }

    public enum PieceKind {
        KING("帅", "将", 'K', 'k'),
        ADVISOR("仕", "士", 'A', 'a'),
        ELEPHANT("相", "象", 'E', 'e'),
        KNIGHT("马", "马", 'H', 'h'),
        ROOK("车", "车", 'R', 'r'),
        CANNON("炮", "炮", 'C', 'c'),
        PAWN("兵", "卒", 'P', 'p');

        private final String redLabel;
        private final String blackLabel;
        private final char redCode;
        private final char blackCode;

        PieceKind(String redLabel, String blackLabel, char redCode, char blackCode) {
            this.redLabel = redLabel;
            this.blackLabel = blackLabel;
            this.redCode = redCode;
            this.blackCode = blackCode;
        }

        public String labelFor(Side side) {
            return side == Side.RED ? redLabel : blackLabel;
        }

        public static PieceKind fromCode(char code) {
            for (PieceKind kind : values()) {
                if (kind.redCode == code || kind.blackCode == code) {
                    return kind;
                }
            }
            throw new IllegalArgumentException("Unknown piece code: " + code);
        }
    }

    public record Piece(Side side, PieceKind kind) {
    }

    public static final class BoardState {
        private final Piece[][] board;

        private BoardState(Piece[][] board) {
            this.board = board;
        }

        public static BoardState fromFen(String fen) {
            String[] segments = fen.split(" ");
            String[] rows = segments[0].split("/");
            Piece[][] board = new Piece[10][9];
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
                    board[y][x++] = new Piece(side, kind);
                }
            }
            return new BoardState(board);
        }

        public Piece get(Position position) {
            if (!position.inBounds()) {
                return null;
            }
            return board[position.y()][position.x()];
        }

        public void move(Position from, Position to) {
            board[to.y()][to.x()] = board[from.y()][from.x()];
            board[from.y()][from.x()] = null;
        }

        public List<Position> positions() {
            List<Position> positions = new ArrayList<>(90);
            for (int y = 0; y < 10; y++) {
                for (int x = 0; x < 9; x++) {
                    positions.add(new Position(x, y));
                }
            }
            return positions;
        }

        public Position findKing(Side side) {
            return positions().stream()
                    .filter(position -> {
                        Piece piece = get(position);
                        return piece != null && piece.side() == side && piece.kind() == PieceKind.KING;
                    })
                    .findFirst()
                    .orElse(null);
        }

        public BoardState copy() {
            Piece[][] copied = new Piece[10][9];
            for (int y = 0; y < 10; y++) {
                copied[y] = Arrays.copyOf(board[y], board[y].length);
            }
            return new BoardState(copied);
        }
    }
}
