package com.tiv.chuhanai.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class XiangqiEngineTest {

    private final XiangqiEngine engine = new XiangqiEngine();

    @Test
    void shouldBuildStandardInitialBoard() {
        BoardState board = engine.initialBoard();

        assertEquals(PieceKind.KING, board.get(Position.parse("e0")).kind());
        assertEquals(PieceKind.KING, board.get(Position.parse("e9")).kind());
        assertEquals(PieceKind.CANNON, board.get(Position.parse("b2")).kind());
        assertEquals("rheakaehr/9/1c5c1/p1p1p1p1p/9/9/P1P1P1P1P/1C5C1/9/RHEAKAEHR w", board.toFen(Side.RED));
    }

    @Test
    void shouldRejectKnightMoveWhenLegBlocked() {
        BoardState board = boardWithSeparatedKings();
        board.place("b0", Piece.red(PieceKind.KNIGHT));
        board.place("b1", Piece.red(PieceKind.PAWN));

        IllegalMoveException exception = assertThrows(IllegalMoveException.class,
                () -> engine.applyMove(board, Side.RED, "b0", "c2", 100, 1));

        assertEquals("MOVE_ILLEGAL", exception.code());
    }

    @Test
    void shouldAllowCannonCaptureWithSingleScreen() {
        BoardState board = boardWithSeparatedKings();
        board.place("b2", Piece.red(PieceKind.CANNON));
        board.place("b5", Piece.red(PieceKind.PAWN));
        board.place("b7", Piece.black(PieceKind.ROOK));

        AppliedMove move = engine.applyMove(board, Side.RED, "b2", "b7", 120, 1);

        assertEquals("ROOK", move.capturedPiece());
        assertEquals(PieceKind.CANNON, board.get(Position.parse("b7")).kind());
    }

    @Test
    void shouldRejectMoveThatLeavesKingsFacing() {
        BoardState board = new BoardState();
        board.place("e0", Piece.red(PieceKind.KING));
        board.place("e9", Piece.black(PieceKind.KING));
        board.place("e1", Piece.red(PieceKind.ROOK));

        IllegalMoveException exception = assertThrows(IllegalMoveException.class,
                () -> engine.applyMove(board, Side.RED, "e1", "f1", 80, 1));

        assertEquals("MOVE_ILLEGAL", exception.code());
    }

    @Test
    void shouldDetectCheckByRook() {
        BoardState board = boardWithSeparatedKings();
        board.place("e4", Piece.black(PieceKind.ROOK));

        assertTrue(engine.isInCheck(board, Side.RED));
        assertTrue(engine.hasAnyLegalMove(board, Side.RED));
    }

    private BoardState boardWithSeparatedKings() {
        BoardState board = new BoardState();
        board.place("e0", Piece.red(PieceKind.KING));
        board.place("e9", Piece.black(PieceKind.KING));
        board.place("e5", Piece.red(PieceKind.PAWN));
        return board;
    }
}
