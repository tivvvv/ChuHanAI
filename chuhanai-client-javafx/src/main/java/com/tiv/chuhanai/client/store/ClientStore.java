package com.tiv.chuhanai.client.store;

import com.tiv.chuhanai.client.net.ClientProtocol.ControlType;
import com.tiv.chuhanai.client.net.ClientProtocol.FinishReason;
import com.tiv.chuhanai.client.net.ClientProtocol.RoomStatus;
import com.tiv.chuhanai.client.net.ClientProtocol.Side;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import java.util.List;

public class ClientStore {
    private final ObjectProperty<Screen> screen = new SimpleObjectProperty<>(Screen.STARTUP);
    private final StringProperty startupStatus = new SimpleStringProperty("正在初始化客户端");
    private final StringProperty notice = new SimpleStringProperty();
    private final StringProperty connectionStatus = new SimpleStringProperty("未连接");
    private final BooleanProperty connected = new SimpleBooleanProperty(false);
    private final BooleanProperty reconnecting = new SimpleBooleanProperty(false);
    private final LongProperty reconnectDeadlineMs = new SimpleLongProperty(0L);
    private final BooleanProperty matching = new SimpleBooleanProperty(false);
    private final BooleanProperty actionLocked = new SimpleBooleanProperty(false);
    private final StringProperty sessionId = new SimpleStringProperty();
    private final StringProperty roomId = new SimpleStringProperty();
    private final StringProperty opponentSessionId = new SimpleStringProperty();
    private final StringProperty boardFen = new SimpleStringProperty();
    private final ObjectProperty<Side> mySide = new SimpleObjectProperty<>();
    private final ObjectProperty<Side> currentTurn = new SimpleObjectProperty<>();
    private final ObjectProperty<RoomStatus> roomStatus = new SimpleObjectProperty<>(RoomStatus.WAITING);
    private final IntegerProperty moveNo = new SimpleIntegerProperty(0);
    private final IntegerProperty redTimeLeftMs = new SimpleIntegerProperty(0);
    private final IntegerProperty blackTimeLeftMs = new SimpleIntegerProperty(0);
    private final StringProperty lastMoveFrom = new SimpleStringProperty();
    private final StringProperty lastMoveTo = new SimpleStringProperty();
    private final ObjectProperty<PendingControlState> pendingControl = new SimpleObjectProperty<>();
    private final ObjectProperty<FinishReason> finishReason = new SimpleObjectProperty<>();
    private final ObjectProperty<Side> winnerSide = new SimpleObjectProperty<>();
    private final BooleanProperty resultVisible = new SimpleBooleanProperty(false);
    private final ObservableList<MoveSummary> moveSummaries = FXCollections.observableArrayList();
    private final ObservableList<UiMessage> chatMessages = FXCollections.observableArrayList();
    private final ObservableList<UiMessage> systemMessages = FXCollections.observableArrayList();

    public ObjectProperty<Screen> screenProperty() {
        return screen;
    }

    public StringProperty startupStatusProperty() {
        return startupStatus;
    }

    public StringProperty noticeProperty() {
        return notice;
    }

    public StringProperty connectionStatusProperty() {
        return connectionStatus;
    }

    public BooleanProperty connectedProperty() {
        return connected;
    }

    public BooleanProperty reconnectingProperty() {
        return reconnecting;
    }

    public LongProperty reconnectDeadlineMsProperty() {
        return reconnectDeadlineMs;
    }

    public BooleanProperty matchingProperty() {
        return matching;
    }

    public BooleanProperty actionLockedProperty() {
        return actionLocked;
    }

    public StringProperty sessionIdProperty() {
        return sessionId;
    }

    public StringProperty roomIdProperty() {
        return roomId;
    }

    public StringProperty opponentSessionIdProperty() {
        return opponentSessionId;
    }

    public StringProperty boardFenProperty() {
        return boardFen;
    }

    public ObjectProperty<Side> mySideProperty() {
        return mySide;
    }

    public ObjectProperty<Side> currentTurnProperty() {
        return currentTurn;
    }

    public ObjectProperty<RoomStatus> roomStatusProperty() {
        return roomStatus;
    }

    public IntegerProperty moveNoProperty() {
        return moveNo;
    }

    public IntegerProperty redTimeLeftMsProperty() {
        return redTimeLeftMs;
    }

    public IntegerProperty blackTimeLeftMsProperty() {
        return blackTimeLeftMs;
    }

    public StringProperty lastMoveFromProperty() {
        return lastMoveFrom;
    }

    public StringProperty lastMoveToProperty() {
        return lastMoveTo;
    }

    public ObjectProperty<PendingControlState> pendingControlProperty() {
        return pendingControl;
    }

    public ObjectProperty<FinishReason> finishReasonProperty() {
        return finishReason;
    }

    public ObjectProperty<Side> winnerSideProperty() {
        return winnerSide;
    }

    public BooleanProperty resultVisibleProperty() {
        return resultVisible;
    }

    public ObservableList<UiMessage> chatMessages() {
        return chatMessages;
    }

    public ObservableList<MoveSummary> moveSummaries() {
        return moveSummaries;
    }

    public ObservableList<UiMessage> systemMessages() {
        return systemMessages;
    }

    public void showScreen(Screen target) {
        screen.set(target);
    }

    public void setConnected(boolean online, String statusText) {
        connected.set(online);
        reconnecting.set(false);
        connectionStatus.set(statusText);
    }

    public void setReconnecting(long deadlineMs, String statusText) {
        connected.set(false);
        reconnecting.set(true);
        reconnectDeadlineMs.set(deadlineMs);
        connectionStatus.set(statusText);
    }

    public void enterRoom(String roomId, Side mySide, String opponentSessionId, String boardFen, Side currentTurn,
                          int moveNo, int redTimeLeftMs, int blackTimeLeftMs) {
        this.roomId.set(roomId);
        this.mySide.set(mySide);
        this.opponentSessionId.set(opponentSessionId);
        this.boardFen.set(boardFen);
        this.currentTurn.set(currentTurn);
        this.moveNo.set(moveNo);
        this.redTimeLeftMs.set(redTimeLeftMs);
        this.blackTimeLeftMs.set(blackTimeLeftMs);
        this.roomStatus.set(RoomStatus.PLAYING);
        this.resultVisible.set(false);
        this.finishReason.set(null);
        this.winnerSide.set(null);
        this.pendingControl.set(null);
        this.chatMessages.clear();
        this.moveSummaries.clear();
        this.lastMoveFrom.set(null);
        this.lastMoveTo.set(null);
        this.systemMessages.clear();
        showScreen(Screen.GAME);
    }

    public void updateBoard(String boardFen, Side currentTurn, int moveNo, int redTimeLeftMs, int blackTimeLeftMs) {
        this.boardFen.set(boardFen);
        this.currentTurn.set(currentTurn);
        this.moveNo.set(moveNo);
        this.redTimeLeftMs.set(redTimeLeftMs);
        this.blackTimeLeftMs.set(blackTimeLeftMs);
    }

    public void clearRoom() {
        roomId.set(null);
        opponentSessionId.set(null);
        boardFen.set(null);
        currentTurn.set(null);
        mySide.set(null);
        roomStatus.set(RoomStatus.WAITING);
        moveNo.set(0);
        redTimeLeftMs.set(0);
        blackTimeLeftMs.set(0);
        matching.set(false);
        actionLocked.set(false);
        pendingControl.set(null);
        resultVisible.set(false);
        finishReason.set(null);
        winnerSide.set(null);
        lastMoveFrom.set(null);
        lastMoveTo.set(null);
        moveSummaries.clear();
        chatMessages.clear();
        systemMessages.clear();
    }

    public String roomId() {
        return roomId.get();
    }

    public Side mySide() {
        return mySide.get();
    }

    public Side currentTurn() {
        return currentTurn.get();
    }

    public int moveNo() {
        return moveNo.get();
    }

    public boolean isMyTurn() {
        return mySide.get() != null && mySide.get() == currentTurn.get();
    }

    public void addChat(UiMessage message) {
        chatMessages.add(message);
    }

    public void replaceChats(List<UiMessage> messages) {
        chatMessages.setAll(messages);
    }

    public void replaceMoveSummaries(List<MoveSummary> summaries) {
        moveSummaries.setAll(summaries);
        updateLastMoveMarkerFromHistory();
    }

    public void addMoveSummary(MoveSummary summary) {
        moveSummaries.add(summary);
        lastMoveFrom.set(summary.from());
        lastMoveTo.set(summary.to());
    }

    public void rollbackLastMoveSummary() {
        if (!moveSummaries.isEmpty()) {
            moveSummaries.remove(moveSummaries.size() - 1);
        }
        updateLastMoveMarkerFromHistory();
    }

    private void updateLastMoveMarkerFromHistory() {
        if (moveSummaries.isEmpty()) {
            lastMoveFrom.set(null);
            lastMoveTo.set(null);
            return;
        }
        MoveSummary latest = moveSummaries.get(moveSummaries.size() - 1);
        lastMoveFrom.set(latest.from());
        lastMoveTo.set(latest.to());
    }

    public void addSystem(String content) {
        systemMessages.add(UiMessage.system(content));
    }

    public void showNotice(String message) {
        notice.set(message);
    }

    public void clearNotice() {
        notice.set(null);
    }

    public enum Screen {
        STARTUP,
        LOBBY,
        MATCHING,
        GAME
    }

    public record UiMessage(
            String sender,
            String content,
            boolean system,
            Long sentAtMs
    ) {
        public static UiMessage system(String content) {
            return new UiMessage("SYSTEM", content, true, null);
        }

        public static UiMessage chat(String senderSessionId, String content, Long sentAtMs) {
            return new UiMessage(senderSessionId, content, false, sentAtMs);
        }
    }

    public record PendingControlState(
            ControlType controlType,
            String fromSessionId,
            long expireAtMs,
            boolean incoming
    ) {
    }

    public record MoveSummary(
            int moveNo,
            Side mover,
            String piece,
            String from,
            String to,
            String capturedPiece,
            long createdAtMs
    ) {
    }
}
