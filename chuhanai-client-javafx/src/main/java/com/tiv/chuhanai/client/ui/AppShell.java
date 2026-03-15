package com.tiv.chuhanai.client.ui;

import com.fasterxml.jackson.databind.JsonNode;
import com.tiv.chuhanai.client.config.ClientConfig;
import com.tiv.chuhanai.client.net.ClientJson;
import com.tiv.chuhanai.client.net.ClientProtocol.ChatBroadcastPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.ChatSendPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.ControlDecisionPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.ControlResultPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.ControlType;
import com.tiv.chuhanai.client.net.ClientProtocol.FinishReason;
import com.tiv.chuhanai.client.net.ClientProtocol.GameOverPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.MatchJoinPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.MatchSuccessPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.MessageType;
import com.tiv.chuhanai.client.net.ClientProtocol.MoveAcceptedPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.MoveRejectedPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.MoveRequestPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.PendingControlPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.RoomStatus;
import com.tiv.chuhanai.client.net.ClientProtocol.ServerEnvelope;
import com.tiv.chuhanai.client.net.ClientProtocol.Side;
import com.tiv.chuhanai.client.net.ClientProtocol.SnapshotPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.SnapshotSyncPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.TimeSyncPayload;
import com.tiv.chuhanai.client.net.ClientProtocol.UndoAppliedPayload;
import com.tiv.chuhanai.client.net.GameWebSocketService;
import com.tiv.chuhanai.client.net.HealthApiClient;
import com.tiv.chuhanai.client.store.ClientStore;
import com.tiv.chuhanai.client.store.ClientStore.MoveSummary;
import com.tiv.chuhanai.client.store.ClientStore.PendingControlState;
import com.tiv.chuhanai.client.store.ClientStore.Screen;
import com.tiv.chuhanai.client.store.ClientStore.UiMessage;
import com.tiv.chuhanai.client.store.SessionStore;
import com.tiv.chuhanai.client.store.SessionStore.StoredSession;
import com.tiv.chuhanai.client.xiangqi.ClientXiangqiSupport;
import com.tiv.chuhanai.client.xiangqi.ClientXiangqiSupport.BoardState;
import com.tiv.chuhanai.client.xiangqi.ClientXiangqiSupport.Piece;
import com.tiv.chuhanai.client.xiangqi.ClientXiangqiSupport.Position;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.util.Duration;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class AppShell extends StackPane {
    private final ClientConfig config;
    private final ClientStore store;
    private final SessionStore sessionStore;
    private final GameWebSocketService webSocketService;
    private final HealthApiClient healthApiClient;
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "client-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final Timeline uiTicker = new Timeline(new KeyFrame(Duration.seconds(1), event -> onSecondTick()));

    private final StackPane startupView = new StackPane();
    private final StackPane lobbyView = new StackPane();
    private final StackPane matchingView = new StackPane();
    private final StackPane gameView = new StackPane();
    private final StackPane reconnectOverlay = new StackPane();
    private final StackPane resultOverlay = new StackPane();

    private final GridPane boardGrid = new GridPane();
    private final StackPane[][] boardCells = new StackPane[10][9];
    private final Label noticeLabel = new Label();
    private final Label startupStatusLabel = new Label();
    private final Label matchingElapsedLabel = new Label();
    private final Label connectionBannerLabel = new Label();
    private final Label roomInfoLabel = new Label();
    private final Label turnInfoLabel = new Label();
    private final Label redClockLabel = new Label();
    private final Label blackClockLabel = new Label();
    private final Label reconnectCountdownLabel = new Label();
    private final Label resultSummaryLabel = new Label();
    private final TextArea chatInput = new TextArea();
    private final ListView<UiMessage> chatListView = new ListView<>();
    private final ListView<MoveSummary> moveHistoryListView = new ListView<>();
    private final ListView<UiMessage> systemListView = new ListView<>();
    private final VBox pendingControlBox = new VBox(8);

    private Optional<StoredSession> currentStoredSession = Optional.empty();
    private Position selectedPosition;
    private Set<Position> highlightedTargets = Set.of();
    private long matchingStartedAtMs;
    private int reconnectGeneration;

    public AppShell(ClientConfig config,
                    ClientStore store,
                    SessionStore sessionStore,
                    GameWebSocketService webSocketService,
                    HealthApiClient healthApiClient) {
        this.config = config;
        this.store = store;
        this.sessionStore = sessionStore;
        this.webSocketService = webSocketService;
        this.healthApiClient = healthApiClient;

        setPadding(new Insets(16));
        setBackground(new Background(new BackgroundFill(Color.web("#f4f6f9"), CornerRadii.EMPTY, Insets.EMPTY)));
        buildUi();
        bindState();

        uiTicker.setCycleCount(Timeline.INDEFINITE);
        uiTicker.play();
    }

    public void bootstrap() {
        store.clearNotice();
        store.showScreen(Screen.STARTUP);
        store.startupStatusProperty().set("正在检查服务健康状态");
        currentStoredSession = sessionStore.load();
        healthApiClient.check().thenAccept(result -> Platform.runLater(() -> {
            if (result.ok()) {
                store.addSystem("健康检查通过");
            } else {
                store.addSystem("健康检查失败，继续尝试建立实时连接");
            }
            connectRealtime();
        })).exceptionally(throwable -> {
            Platform.runLater(() -> {
                store.addSystem("健康检查不可用，继续尝试建立实时连接");
                connectRealtime();
            });
            return null;
        });
    }

    private void connectRealtime() {
        store.startupStatusProperty().set("正在建立实时连接");
        webSocketService.connect(currentStoredSession, new WsListener())
                .exceptionally(throwable -> {
                    Platform.runLater(() -> {
                        store.setConnected(false, "连接失败");
                        store.startupStatusProperty().set("建立连接失败: " + rootMessage(throwable));
                        store.showNotice("请确认服务端已启动，或稍后重试");
                    });
                    return null;
                });
    }

    private void buildUi() {
        noticeLabel.setStyle("-fx-background-color:#243447;-fx-text-fill:white;-fx-padding:8 12 8 12;-fx-background-radius:8;");
        noticeLabel.visibleProperty().bind(Bindings.isNotEmpty(store.noticeProperty()));
        noticeLabel.managedProperty().bind(noticeLabel.visibleProperty());
        noticeLabel.textProperty().bind(store.noticeProperty());

        buildStartupView();
        buildLobbyView();
        buildMatchingView();
        buildGameView();

        StackPane topNotice = new StackPane(noticeLabel);
        StackPane.setAlignment(noticeLabel, Pos.TOP_CENTER);
        StackPane.setMargin(noticeLabel, new Insets(0, 0, 12, 0));
        topNotice.setPickOnBounds(false);
        topNotice.setMouseTransparent(true);

        getChildren().addAll(startupView, lobbyView, matchingView, gameView, topNotice);
    }

    private void buildStartupView() {
        Label title = new Label("ChuHanAI 客户端启动中");
        title.setFont(Font.font(26));
        startupStatusLabel.setStyle("-fx-font-size:15;");
        startupStatusLabel.textProperty().bind(store.startupStatusProperty());

        Button retryButton = new Button("重试连接");
        retryButton.setOnAction(event -> bootstrap());

        VBox card = cardBox(title, startupStatusLabel, retryButton);
        card.setAlignment(Pos.CENTER);
        startupView.getChildren().add(card);
    }

    private void buildLobbyView() {
        Label title = new Label("大厅");
        title.setFont(Font.font(28));

        Label sessionLabel = new Label();
        sessionLabel.textProperty().bind(Bindings.concat("匿名会话: ")
                .concat(Bindings.when(Bindings.isEmpty(store.sessionIdProperty())).then("未分配").otherwise(store.sessionIdProperty())));

        Label connectionLabel = new Label();
        connectionLabel.textProperty().bind(Bindings.concat("连接状态: ").concat(store.connectionStatusProperty()));

        Button matchButton = new Button("开始匹配");
        matchButton.setPrefWidth(180);
        matchButton.disableProperty().bind(store.connectedProperty().not().or(store.matchingProperty()));
        matchButton.setOnAction(event -> startMatching());

        Button reconnectButton = new Button("重新连接");
        reconnectButton.setOnAction(event -> bootstrap());

        VBox card = cardBox(title, sessionLabel, connectionLabel, matchButton, reconnectButton);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setMaxWidth(460);
        lobbyView.getChildren().add(card);
    }

    private void buildMatchingView() {
        Label title = new Label("正在匹配对手");
        title.setFont(Font.font(28));

        matchingElapsedLabel.setStyle("-fx-font-size:18;");
        Label hint = new Label("匹配成功后会自动进入对局页");
        Button cancelButton = new Button("取消匹配");
        cancelButton.setOnAction(event -> cancelMatching());

        VBox card = cardBox(title, matchingElapsedLabel, hint, cancelButton);
        card.setAlignment(Pos.CENTER);
        matchingView.getChildren().add(card);
    }

    private void buildGameView() {
        BorderPane layout = new BorderPane();
        layout.setPadding(new Insets(8));
        layout.setLeft(new VBox(12, buildStatusHeader(), buildBoardPane()));
        layout.setCenter(new Region());
        VBox right = new VBox(12, buildMoveHistoryPanel(), buildSystemPanel(), buildChatPanel(), buildControlPanel());
        right.setPrefWidth(420);
        layout.setRight(right);
        BorderPane.setMargin(right, new Insets(0, 0, 0, 16));

        buildReconnectOverlay();
        buildResultOverlay();
        StackPane scene = new StackPane(layout, reconnectOverlay, resultOverlay);
        gameView.getChildren().add(scene);
    }

    private Node buildStatusHeader() {
        connectionBannerLabel.textProperty().bind(Bindings.concat("网络状态: ").concat(store.connectionStatusProperty()));
        roomInfoLabel.setStyle("-fx-font-size:16;");
        turnInfoLabel.setStyle("-fx-font-size:16;");
        redClockLabel.setStyle("-fx-font-size:16;-fx-text-fill:#b22222;");
        blackClockLabel.setStyle("-fx-font-size:16;-fx-text-fill:#222;");
        return cardBox(connectionBannerLabel, roomInfoLabel, turnInfoLabel, redClockLabel, blackClockLabel);
    }

    private Node buildBoardPane() {
        boardGrid.setHgap(4);
        boardGrid.setVgap(4);
        boardGrid.setPadding(new Insets(12));
        boardGrid.setBackground(new Background(new BackgroundFill(Color.web("#f6d8a8"), new CornerRadii(12), Insets.EMPTY)));
        boardGrid.setBorder(new Border(new BorderStroke(Color.web("#8b5a2b"), BorderStrokeStyle.SOLID, new CornerRadii(12), new BorderWidths(2))));
        for (int displayRow = 0; displayRow < 10; displayRow++) {
            for (int displayCol = 0; displayCol < 9; displayCol++) {
                final int cellRow = displayRow;
                final int cellCol = displayCol;
                StackPane cell = new StackPane();
                cell.setPrefSize(64, 64);
                cell.setMinSize(64, 64);
                cell.setMaxSize(64, 64);
                cell.setOnMouseClicked(event -> onBoardClicked(logicalPosition(cellRow, cellCol)));
                boardCells[displayRow][displayCol] = cell;
                boardGrid.add(cell, displayCol, displayRow);
            }
        }
        VBox wrapper = new VBox(boardGrid);
        wrapper.setAlignment(Pos.CENTER);
        return wrapper;
    }

    private Node buildSystemPanel() {
        systemListView.setItems(store.systemMessages());
        systemListView.setCellFactory(list -> new MessageCell());
        systemListView.setPrefHeight(160);
        return cardBox(new Label("系统消息"), systemListView);
    }

    private Node buildMoveHistoryPanel() {
        moveHistoryListView.setItems(store.moveSummaries());
        moveHistoryListView.setCellFactory(list -> new MoveSummaryCell());
        moveHistoryListView.setPrefHeight(170);
        return cardBox(new Label("最近走子"), moveHistoryListView);
    }

    private Node buildChatPanel() {
        chatListView.setItems(store.chatMessages());
        chatListView.setCellFactory(list -> new MessageCell());
        chatListView.setPrefHeight(220);
        chatInput.setPromptText("输入聊天内容，最多 300 字");
        chatInput.setWrapText(true);
        chatInput.setPrefRowCount(3);

        Button sendButton = new Button("发送");
        sendButton.disableProperty().bind(store.connectedProperty().not().or(store.roomIdProperty().isEmpty()));
        sendButton.setOnAction(event -> sendChat());

        VBox panel = cardBox(new Label("聊天"), chatListView, chatInput, sendButton);
        VBox.setVgrow(chatListView, Priority.ALWAYS);
        return panel;
    }

    private Node buildControlPanel() {
        Button undoButton = new Button("悔棋");
        Button drawButton = new Button("求和");
        Button resignButton = new Button("认输");
        BooleanBinding disabled = store.connectedProperty().not()
                .or(store.roomIdProperty().isEmpty())
                .or(store.resultVisibleProperty())
                .or(store.pendingControlProperty().isNotNull())
                .or(store.reconnectingProperty())
                .or(store.actionLockedProperty());

        undoButton.disableProperty().bind(disabled);
        drawButton.disableProperty().bind(disabled);
        resignButton.disableProperty().bind(disabled);

        undoButton.setOnAction(event -> sendControlRequest(ControlType.UNDO));
        drawButton.setOnAction(event -> sendControlRequest(ControlType.DRAW));
        resignButton.setOnAction(event -> confirmResign());

        pendingControlBox.setPadding(new Insets(8));
        pendingControlBox.setBackground(new Background(new BackgroundFill(Color.web("#eef3f8"), new CornerRadii(8), Insets.EMPTY)));
        pendingControlBox.getChildren().setAll(new Label("当前无未决控制请求"));
        return cardBox(new Label("对局控制"), new HBox(8, undoButton, drawButton, resignButton), pendingControlBox);
    }

    private void buildReconnectOverlay() {
        Label title = new Label("正在重连");
        title.setFont(Font.font(24));
        reconnectCountdownLabel.setStyle("-fx-font-size:16;");
        VBox card = cardBox(title, reconnectCountdownLabel);
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(320);
        reconnectOverlay.getChildren().add(card);
        reconnectOverlay.setBackground(new Background(new BackgroundFill(Color.rgb(0, 0, 0, 0.25), CornerRadii.EMPTY, Insets.EMPTY)));
    }

    private void buildResultOverlay() {
        Label title = new Label("对局结束");
        title.setFont(Font.font(26));
        resultSummaryLabel.setStyle("-fx-font-size:16;");

        Button backButton = new Button("返回大厅");
        backButton.setOnAction(event -> returnToLobby(false));
        Button rematchButton = new Button("再来一局");
        rematchButton.setOnAction(event -> returnToLobby(true));

        VBox card = cardBox(title, resultSummaryLabel, new HBox(8, backButton, rematchButton));
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(360);
        resultOverlay.getChildren().add(card);
        resultOverlay.setBackground(new Background(new BackgroundFill(Color.rgb(0, 0, 0, 0.35), CornerRadii.EMPTY, Insets.EMPTY)));
    }

    private void bindState() {
        startupView.visibleProperty().bind(store.screenProperty().isEqualTo(Screen.STARTUP));
        startupView.managedProperty().bind(startupView.visibleProperty());
        lobbyView.visibleProperty().bind(store.screenProperty().isEqualTo(Screen.LOBBY));
        lobbyView.managedProperty().bind(lobbyView.visibleProperty());
        matchingView.visibleProperty().bind(store.screenProperty().isEqualTo(Screen.MATCHING));
        matchingView.managedProperty().bind(matchingView.visibleProperty());
        gameView.visibleProperty().bind(store.screenProperty().isEqualTo(Screen.GAME));
        gameView.managedProperty().bind(gameView.visibleProperty());
        reconnectOverlay.visibleProperty().bind(store.reconnectingProperty());
        reconnectOverlay.managedProperty().bind(reconnectOverlay.visibleProperty());
        resultOverlay.visibleProperty().bind(store.resultVisibleProperty());
        resultOverlay.managedProperty().bind(resultOverlay.visibleProperty());

        store.boardFenProperty().addListener((obs, oldValue, newValue) -> renderBoard());
        store.mySideProperty().addListener((obs, oldValue, newValue) -> renderBoard());
        store.lastMoveFromProperty().addListener((obs, oldValue, newValue) -> renderBoard());
        store.lastMoveToProperty().addListener((obs, oldValue, newValue) -> renderBoard());
        store.pendingControlProperty().addListener((obs, oldValue, newValue) -> refreshPendingControlBox());
        store.currentTurnProperty().addListener((obs, oldValue, newValue) -> refreshStatusHeader());
        store.roomIdProperty().addListener((obs, oldValue, newValue) -> refreshStatusHeader());
        store.redTimeLeftMsProperty().addListener((obs, oldValue, newValue) -> refreshStatusHeader());
        store.blackTimeLeftMsProperty().addListener((obs, oldValue, newValue) -> refreshStatusHeader());
        store.finishReasonProperty().addListener((obs, oldValue, newValue) -> refreshResultSummary());
        store.winnerSideProperty().addListener((obs, oldValue, newValue) -> refreshResultSummary());
    }

    private void startMatching() {
        try {
            webSocketService.send(MessageType.MATCH_JOIN, null, new MatchJoinPayload(config.clientVersion()));
            store.matchingProperty().set(true);
            matchingStartedAtMs = Instant.now().toEpochMilli();
            store.showScreen(Screen.MATCHING);
            store.showNotice("已发送匹配请求");
        } catch (Exception e) {
            store.showNotice("发送匹配请求失败: " + rootMessage(e));
        }
    }

    private void cancelMatching() {
        try {
            webSocketService.send(MessageType.MATCH_CANCEL, null, Map.of());
        } catch (Exception e) {
            store.showNotice("取消匹配失败: " + rootMessage(e));
        }
    }

    private void sendChat() {
        String content = chatInput.getText() == null ? "" : chatInput.getText().trim();
        if (content.isEmpty()) {
            return;
        }
        if (content.length() > 300) {
            store.showNotice("聊天内容不能超过 300 字");
            return;
        }
        try {
            webSocketService.send(MessageType.CHAT_SEND, store.roomId(), new ChatSendPayload(content));
            chatInput.clear();
        } catch (Exception e) {
            store.showNotice("发送聊天失败: " + rootMessage(e));
        }
    }

    private void sendControlRequest(ControlType controlType) {
        try {
            store.actionLockedProperty().set(true);
            MessageType type = controlType == ControlType.UNDO ? MessageType.UNDO_REQUEST : MessageType.DRAW_REQUEST;
            webSocketService.send(type, store.roomId(), Map.of());
            store.pendingControlProperty().set(new PendingControlState(controlType, store.sessionIdProperty().get(),
                    Instant.now().plusSeconds(15).toEpochMilli(), false));
            store.addSystem("已发起" + controlTypeLabel(controlType) + "请求，等待对手响应");
        } catch (Exception e) {
            store.actionLockedProperty().set(false);
            store.showNotice("发送控制请求失败: " + rootMessage(e));
        }
    }

    private void confirmResign() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("确认认输");
        alert.setHeaderText("确认立即结束当前对局吗？");
        alert.showAndWait().ifPresent(buttonType -> {
            if (buttonType.getButtonData().isDefaultButton()) {
                try {
                    store.actionLockedProperty().set(true);
                    webSocketService.send(MessageType.RESIGN, store.roomId(), Map.of());
                } catch (Exception e) {
                    store.actionLockedProperty().set(false);
                    store.showNotice("发送认输失败: " + rootMessage(e));
                }
            }
        });
    }

    private void respondPendingControl(boolean accept) {
        PendingControlState pending = store.pendingControlProperty().get();
        if (pending == null) {
            return;
        }
        try {
            MessageType type = pending.controlType() == ControlType.UNDO ? MessageType.UNDO_RESPONSE : MessageType.DRAW_RESPONSE;
            webSocketService.send(type, store.roomId(), new ControlDecisionPayload(accept ? "ACCEPT" : "REJECT"));
            store.addSystem((accept ? "已同意" : "已拒绝") + "对手的" + controlTypeLabel(pending.controlType()) + "请求");
            store.pendingControlProperty().set(null);
            store.actionLockedProperty().set(false);
        } catch (Exception e) {
            store.showNotice("响应控制请求失败: " + rootMessage(e));
        }
    }

    private void onBoardClicked(Position position) {
        if (!store.connectedProperty().get() || store.resultVisibleProperty().get() || store.reconnectingProperty().get()) {
            return;
        }
        BoardState board = currentBoard();
        if (board == null) {
            return;
        }
        Piece clicked = board.get(position);
        if (selectedPosition == null) {
            if (clicked != null && clicked.side() == store.mySide() && store.isMyTurn() && !store.actionLockedProperty().get()) {
                selectedPosition = position;
                highlightedTargets = new HashSet<>(ClientXiangqiSupport.legalTargets(board, store.mySide(), position));
                renderBoard();
            }
            return;
        }
        if (selectedPosition.equals(position)) {
            clearSelection();
            return;
        }
        if (highlightedTargets.contains(position)) {
            Position from = selectedPosition;
            clearSelection();
            sendMove(from, position);
            return;
        }
        if (clicked != null && clicked.side() == store.mySide() && store.isMyTurn()) {
            selectedPosition = position;
            highlightedTargets = new HashSet<>(ClientXiangqiSupport.legalTargets(board, store.mySide(), position));
            renderBoard();
            return;
        }
        clearSelection();
    }

    private void sendMove(Position from, Position to) {
        BoardState board = currentBoard();
        if (board == null) {
            return;
        }
        Piece piece = board.get(from);
        if (piece == null) {
            return;
        }
        try {
            store.actionLockedProperty().set(true);
            webSocketService.send(MessageType.MOVE_REQUEST, store.roomId(),
                    new MoveRequestPayload(store.moveNo() + 1, from.toString(), to.toString(), piece.kind().name()));
        } catch (Exception e) {
            store.actionLockedProperty().set(false);
            store.showNotice("发送走子失败: " + rootMessage(e));
        }
    }

    private void clearSelection() {
        selectedPosition = null;
        highlightedTargets = Set.of();
        renderBoard();
    }

    private BoardState currentBoard() {
        String fen = store.boardFenProperty().get();
        if (fen == null || fen.isBlank()) {
            return null;
        }
        return BoardState.fromFen(fen);
    }

    private void renderBoard() {
        BoardState board = currentBoard();
        for (int displayRow = 0; displayRow < 10; displayRow++) {
            for (int displayCol = 0; displayCol < 9; displayCol++) {
                StackPane cell = boardCells[displayRow][displayCol];
                cell.getChildren().clear();
                Position logical = logicalPosition(displayRow, displayCol);
                boolean selected = logical.equals(selectedPosition);
                boolean highlighted = highlightedTargets.contains(logical);
                boolean recentFrom = logical.toString().equals(store.lastMoveFromProperty().get());
                boolean recentTo = logical.toString().equals(store.lastMoveToProperty().get());
                String background = selected ? "#c0d8ff"
                        : recentTo ? "#f8e7a3"
                        : recentFrom ? "#f2f0c8"
                        : highlighted ? "#dff5d6"
                        : "#f4c98b";
                cell.setBackground(new Background(new BackgroundFill(Color.web(background), new CornerRadii(10), Insets.EMPTY)));
                if (board == null) {
                    continue;
                }
                Piece piece = board.get(logical);
                if (piece == null) {
                    continue;
                }
                Circle pieceCircle = new Circle(24, Color.web(piece.side() == Side.RED ? "#fff5f5" : "#f4f4f4"));
                pieceCircle.setStroke(piece.side() == Side.RED ? Color.web("#b22222") : Color.web("#222"));
                pieceCircle.setStrokeWidth(2);
                Label label = new Label(piece.kind().labelFor(piece.side()));
                label.setTextFill(piece.side() == Side.RED ? Color.web("#b22222") : Color.web("#222"));
                label.setFont(Font.font(18));
                cell.getChildren().addAll(pieceCircle, label);
            }
        }
    }

    private void refreshStatusHeader() {
        String roomText = store.roomId() == null ? "房间: -" : "房间: " + store.roomId();
        String sideText = store.mySide() == null ? "-" : (store.mySide() == Side.RED ? "红方" : "黑方");
        roomInfoLabel.setText(roomText + " | 我方: " + sideText);
        String turnText = store.currentTurn() == null ? "-" : (store.currentTurn() == Side.RED ? "红方走" : "黑方走");
        turnInfoLabel.setText("当前回合: " + turnText + " | 步数: " + store.moveNoProperty().get());
        redClockLabel.setText("红方剩余: " + formatMs(store.redTimeLeftMsProperty().get()));
        blackClockLabel.setText("黑方剩余: " + formatMs(store.blackTimeLeftMsProperty().get()));
    }

    private void refreshPendingControlBox() {
        pendingControlBox.getChildren().clear();
        PendingControlState pending = store.pendingControlProperty().get();
        if (pending == null) {
            pendingControlBox.getChildren().add(new Label("当前无未决控制请求"));
            return;
        }
        String title = pending.controlType() == ControlType.UNDO ? "悔棋" : "求和";
        pendingControlBox.getChildren().add(new Label(pending.incoming()
                ? "对手发起了" + title + "请求"
                : "已发起" + title + "请求，等待对手响应"));
        if (pending.incoming()) {
            Button accept = new Button("同意");
            Button reject = new Button("拒绝");
            accept.setOnAction(event -> respondPendingControl(true));
            reject.setOnAction(event -> respondPendingControl(false));
            pendingControlBox.getChildren().add(new HBox(8, accept, reject));
        }
    }

    private void refreshResultSummary() {
        FinishReason reason = store.finishReasonProperty().get();
        Side winner = store.winnerSideProperty().get();
        if (reason == null) {
            resultSummaryLabel.setText("等待对局结束信息");
            return;
        }
        String reasonText = switch (reason) {
            case CHECKMATE -> "将死";
            case RESIGN -> "认输";
            case TIMEOUT -> "超时判负";
            case DRAW_AGREED, DRAW_STALEMATE -> "和棋";
            case RECONNECT_TIMEOUT -> "重连超时";
        };
        String winnerText = winner == null ? "无胜方" : (winner == Side.RED ? "红方获胜" : "黑方获胜");
        resultSummaryLabel.setText(winnerText + "\n结束原因: " + reasonText + "\n最终步数: " + store.moveNoProperty().get());
    }

    private void beginReconnectLoop() {
        reconnectGeneration++;
        int generation = reconnectGeneration;
        long deadline = Instant.now().plusSeconds(30).toEpochMilli();
        store.setReconnecting(deadline, "连接中断，正在尝试恢复");
        scheduleReconnectAttempt(generation, deadline, 200);
    }

    private void scheduleReconnectAttempt(int generation, long deadline, long delayMs) {
        reconnectExecutor.schedule(() -> {
            if (generation != reconnectGeneration) {
                return;
            }
            if (Instant.now().toEpochMilli() >= deadline) {
                Platform.runLater(() -> {
                    store.reconnectingProperty().set(false);
                    store.showNotice("超出 30 秒重连窗口，本局已结束");
                    store.resultVisibleProperty().set(true);
                    store.finishReasonProperty().set(FinishReason.RECONNECT_TIMEOUT);
                    sessionStore.clearRoomContext();
                });
                return;
            }
            currentStoredSession = sessionStore.load();
            webSocketService.connect(currentStoredSession, new WsListener()).exceptionally(throwable -> {
                scheduleReconnectAttempt(generation, deadline, Math.min(delayMs * 2, 2000));
                return null;
            });
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    private void returnToLobby(boolean prepareRematch) {
        clearSelection();
        store.clearRoom();
        sessionStore.clearRoomContext();
        store.showScreen(Screen.LOBBY);
        if (prepareRematch) {
            startMatching();
        }
    }

    private void onSecondTick() {
        if (store.matchingProperty().get()) {
            long elapsedSeconds = Math.max((Instant.now().toEpochMilli() - matchingStartedAtMs) / 1000, 0);
            matchingElapsedLabel.setText("已等待 " + elapsedSeconds + " 秒");
        }
        if (store.reconnectingProperty().get()) {
            long remaining = Math.max((store.reconnectDeadlineMsProperty().get() - Instant.now().toEpochMilli()) / 1000, 0);
            reconnectCountdownLabel.setText("剩余恢复时间: " + remaining + " 秒");
        }
        if (store.screenProperty().get() == Screen.GAME
                && store.connectedProperty().get()
                && !store.resultVisibleProperty().get()
                && !store.reconnectingProperty().get()
                && store.currentTurn() != null) {
            if (store.currentTurn() == Side.RED && store.redTimeLeftMsProperty().get() > 0) {
                store.redTimeLeftMsProperty().set(Math.max(0, store.redTimeLeftMsProperty().get() - 1000));
            } else if (store.currentTurn() == Side.BLACK && store.blackTimeLeftMsProperty().get() > 0) {
                store.blackTimeLeftMsProperty().set(Math.max(0, store.blackTimeLeftMsProperty().get() - 1000));
            }
        }
    }

    private void saveSessionSnapshot() {
        String sessionId = store.sessionIdProperty().get();
        String resumeToken = webSocketService.resumeToken();
        if (sessionId == null || resumeToken == null) {
            return;
        }
        if (store.roomStatusProperty().get() == RoomStatus.FINISHED) {
            sessionStore.clearRoomContext();
            return;
        }
        sessionStore.save(sessionId, resumeToken, store.roomId(), store.moveNo(), store.mySide());
    }

    private <T> T payload(ServerEnvelope envelope, Class<T> type) {
        JsonNode payload = envelope.payload();
        return payload == null || payload.isNull() ? null : ClientJson.MAPPER.convertValue(payload, type);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? throwable.toString() : current.getMessage();
    }

    private String formatMs(int millis) {
        int totalSeconds = Math.max(millis / 1000, 0);
        return String.format("%02d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private Position logicalPosition(int displayRow, int displayCol) {
        if (store.mySide() == Side.BLACK) {
            return new Position(8 - displayCol, displayRow);
        }
        return new Position(displayCol, 9 - displayRow);
    }

    private VBox cardBox(Node... children) {
        VBox box = new VBox(12, children);
        box.setPadding(new Insets(18));
        box.setBackground(new Background(new BackgroundFill(Color.WHITE, new CornerRadii(12), Insets.EMPTY)));
        box.setBorder(new Border(new BorderStroke(Color.web("#dde3ea"), BorderStrokeStyle.SOLID, new CornerRadii(12), BorderWidths.DEFAULT)));
        return box;
    }

    private void handleEnvelope(ServerEnvelope envelope) {
        if (Boolean.FALSE.equals(envelope.success())) {
            handleFailureEnvelope(envelope);
            return;
        }
        switch (envelope.type()) {
            case MATCH_JOINED -> {
                store.matchingProperty().set(true);
                matchingStartedAtMs = Instant.now().toEpochMilli();
                store.showScreen(Screen.MATCHING);
                store.showNotice("已进入匹配队列");
            }
            case MATCH_CANCELLED -> {
                store.matchingProperty().set(false);
                store.showScreen(Screen.LOBBY);
                store.showNotice("已取消匹配");
            }
            case MATCH_SUCCESS -> handleMatchSuccess(payload(envelope, MatchSuccessPayload.class));
            case MOVE_ACCEPTED -> handleMoveAccepted(payload(envelope, MoveAcceptedPayload.class));
            case MOVE_REJECTED -> handleMoveRejected(envelope, payload(envelope, MoveRejectedPayload.class));
            case TIME_SYNC -> handleTimeSync(payload(envelope, TimeSyncPayload.class));
            case CHAT_BROADCAST -> handleChatBroadcast(payload(envelope, ChatBroadcastPayload.class));
            case UNDO_PENDING -> handlePendingControl(payload(envelope, PendingControlPayload.class), ControlType.UNDO);
            case DRAW_PENDING -> handlePendingControl(payload(envelope, PendingControlPayload.class), ControlType.DRAW);
            case DRAW_RESPONSE -> handleDrawResponse(payload(envelope, ControlResultPayload.class));
            case UNDO_APPLIED -> handleUndoApplied(payload(envelope, UndoAppliedPayload.class));
            case UNDO_REJECTED, DRAW_REJECTED -> handleControlRejected(envelope, payload(envelope, ControlResultPayload.class));
            case SNAPSHOT_SYNCED -> handleSnapshot(payload(envelope, SnapshotPayload.class));
            case GAME_OVER -> handleGameOver(payload(envelope, GameOverPayload.class));
            case PONG -> store.connectionStatusProperty().set("已连接");
            default -> {
            }
        }
    }

    private void handleFailureEnvelope(ServerEnvelope envelope) {
        String message = envelope.error() == null ? "服务端返回失败" : envelope.error().message();
        if (envelope.type() == MessageType.MOVE_REJECTED) {
            handleMoveRejected(envelope, payload(envelope, MoveRejectedPayload.class));
        } else if (envelope.type() == MessageType.UNDO_REJECTED || envelope.type() == MessageType.DRAW_REJECTED) {
            handleControlRejected(envelope, payload(envelope, ControlResultPayload.class));
        } else if (envelope.type() == MessageType.SNAPSHOT_SYNCED) {
            sessionStore.clearRoomContext();
            store.clearRoom();
            store.showScreen(Screen.LOBBY);
            store.showNotice("房间快照恢复失败，已返回大厅: " + message);
        } else {
            store.showNotice(message);
        }
        store.actionLockedProperty().set(false);
        if (envelope.error() != null && "UNAUTHORIZED".equals(envelope.error().code())) {
            sessionStore.clearAll();
        }
    }

    private void handleMatchSuccess(MatchSuccessPayload payload) {
        if (payload == null) {
            return;
        }
        store.matchingProperty().set(false);
        clearSelection();
        int baseTime = payload.baseTimeMs() == null ? 600_000 : payload.baseTimeMs();
        int increment = payload.incrementMs() == null ? 15_000 : payload.incrementMs();
        Side initialTurn = payload.initialFen() != null && payload.initialFen().endsWith(" b") ? Side.BLACK : Side.RED;
        store.enterRoom(payload.roomId(), payload.mySide(), payload.opponentSessionId(), payload.initialFen(),
                initialTurn, 0, baseTime, baseTime);
        saveSessionSnapshot();
        store.addSystem("匹配成功，已进入房间 " + payload.roomId() + "，你当前执"
                + (payload.mySide() == Side.RED ? "红" : "黑")
                + "，用时 " + formatMs(baseTime) + "，步增 " + (increment / 1000) + " 秒");
    }

    private void handleMoveAccepted(MoveAcceptedPayload payload) {
        if (payload == null) {
            return;
        }
        clearSelection();
        Side mover = payload.nextTurn() == null ? store.currentTurn() : payload.nextTurn().opposite();
        store.updateBoard(payload.boardFenAfter(), payload.nextTurn(),
                payload.moveNo() == null ? store.moveNo() + 1 : payload.moveNo(),
                payload.redTimeLeftMs() == null ? store.redTimeLeftMsProperty().get() : payload.redTimeLeftMs(),
                payload.blackTimeLeftMs() == null ? store.blackTimeLeftMsProperty().get() : payload.blackTimeLeftMs());
        MoveSummary summary = new MoveSummary(
                payload.moveNo() == null ? store.moveNo() : payload.moveNo(),
                mover,
                payload.piece(),
                payload.from(),
                payload.to(),
                payload.capturedPiece(),
                Instant.now().toEpochMilli()
        );
        store.addMoveSummary(summary);
        store.actionLockedProperty().set(false);
        store.addSystem(formatMoveSummary(summary));
        saveSessionSnapshot();
    }

    private void handleMoveRejected(ServerEnvelope envelope, MoveRejectedPayload payload) {
        clearSelection();
        if (payload != null && payload.authoritativeFen() != null) {
            Side turn = payload.authoritativeFen().endsWith(" w") ? Side.RED : Side.BLACK;
            int moveNo = payload.expectedMoveNo() == null ? store.moveNo() : Math.max(payload.expectedMoveNo() - 1, 0);
            store.updateBoard(payload.authoritativeFen(), turn, moveNo,
                    store.redTimeLeftMsProperty().get(), store.blackTimeLeftMsProperty().get());
        }
        store.actionLockedProperty().set(false);
        store.showNotice(envelope.error() == null ? "走子被拒绝" : envelope.error().message());
    }

    private void handleTimeSync(TimeSyncPayload payload) {
        if (payload == null) {
            return;
        }
        store.updateBoard(store.boardFenProperty().get(),
                payload.currentTurn() == null ? store.currentTurn() : payload.currentTurn(),
                payload.moveNo() == null ? store.moveNo() : payload.moveNo(),
                payload.redTimeLeftMs() == null ? store.redTimeLeftMsProperty().get() : payload.redTimeLeftMs(),
                payload.blackTimeLeftMs() == null ? store.blackTimeLeftMsProperty().get() : payload.blackTimeLeftMs());
    }

    private void handleChatBroadcast(ChatBroadcastPayload payload) {
        if (payload != null) {
            store.addChat(new UiMessage(payload.senderSessionId(), payload.content(), false));
        }
    }

    private void handlePendingControl(PendingControlPayload payload, ControlType fallbackType) {
        ControlType type = payload != null && payload.controlType() != null ? payload.controlType() : fallbackType;
        String from = payload == null ? null : payload.fromSessionId();
        long expireAtMs = payload == null || payload.expireAtMs() == null ? Instant.now().plusSeconds(15).toEpochMilli() : payload.expireAtMs();
        boolean incoming = from != null && !from.equals(store.sessionIdProperty().get());
        store.pendingControlProperty().set(new PendingControlState(type, from, expireAtMs, incoming));
        store.actionLockedProperty().set(false);
        store.addSystem(incoming
                ? "对手发起了" + controlTypeLabel(type) + "请求"
                : "你的" + controlTypeLabel(type) + "请求已送达，等待对手响应");
    }

    private void handleDrawResponse(ControlResultPayload payload) {
        store.pendingControlProperty().set(null);
        store.actionLockedProperty().set(false);
        if (payload != null && "ACCEPT".equalsIgnoreCase(payload.decision())) {
            store.addSystem("对局双方已同意求和");
        }
    }

    private void handleControlRejected(ServerEnvelope envelope, ControlResultPayload payload) {
        PendingControlState pending = store.pendingControlProperty().get();
        ControlType type = envelope.type() == MessageType.UNDO_REJECTED ? ControlType.UNDO : ControlType.DRAW;
        boolean outgoing = pending != null && pending.controlType() == type && !pending.incoming();
        boolean incoming = pending != null && pending.controlType() == type && pending.incoming();
        store.pendingControlProperty().set(null);
        store.actionLockedProperty().set(false);

        String label = controlTypeLabel(type);
        String decision = payload == null || payload.decision() == null ? "" : payload.decision().toUpperCase();
        String message;
        if (Boolean.FALSE.equals(envelope.success()) && envelope.error() != null && envelope.error().message() != null) {
            message = envelope.error().message();
        } else if ("TIMEOUT".equals(decision)) {
            message = outgoing ? "对手未及时响应你的" + label + "请求" : label + "请求已超时，系统按拒绝处理";
        } else if (outgoing) {
            message = "对方拒绝了你的" + label + "请求";
        } else if (incoming) {
            message = "你已拒绝对手的" + label + "请求";
        } else {
            message = label + "请求未通过";
        }
        store.addSystem(message);
        store.showNotice(message);
    }

    private void handleUndoApplied(UndoAppliedPayload payload) {
        if (payload == null) {
            return;
        }
        clearSelection();
        store.pendingControlProperty().set(null);
        store.actionLockedProperty().set(false);
        store.addSystem("悔棋已生效，棋局已回退到上一步");
        store.updateBoard(payload.boardFen(), payload.currentTurn(),
                payload.moveNo() == null ? Math.max(store.moveNo() - 1, 0) : payload.moveNo(),
                payload.redTimeLeftMs() == null ? store.redTimeLeftMsProperty().get() : payload.redTimeLeftMs(),
                payload.blackTimeLeftMs() == null ? store.blackTimeLeftMsProperty().get() : payload.blackTimeLeftMs());
        store.rollbackLastMoveSummary();
        saveSessionSnapshot();
    }

    private void handleSnapshot(SnapshotPayload payload) {
        if (payload == null || payload.room() == null) {
            return;
        }
        store.setConnected(true, "已连接");
        store.roomStatusProperty().set(payload.room().status());
        store.showScreen(Screen.GAME);
        if (store.mySide() == null) {
            store.mySideProperty().set(currentStoredSession.map(StoredSession::mySide).orElse(null));
        }
        store.roomIdProperty().set(payload.room().roomId());
        store.updateBoard(payload.room().boardFen(), payload.room().currentTurn(),
                payload.room().moveNo() == null ? store.moveNo() : payload.room().moveNo(),
                payload.room().redTimeLeftMs() == null ? store.redTimeLeftMsProperty().get() : payload.room().redTimeLeftMs(),
                payload.room().blackTimeLeftMs() == null ? store.blackTimeLeftMsProperty().get() : payload.room().blackTimeLeftMs());
        List<MoveSummary> recentMoves = payload.recentMoves() == null ? List.of() : payload.recentMoves().stream()
                .map(move -> new MoveSummary(
                        move.moveNo() == null ? 0 : move.moveNo(),
                        inferMover(move.moveNo() == null ? 0 : move.moveNo()),
                        move.piece(),
                        move.from(),
                        move.to(),
                        null,
                        move.createdAtMs() == null ? 0L : move.createdAtMs()
                ))
                .toList();
        store.replaceMoveSummaries(recentMoves);
        store.pendingControlProperty().set(null);
        if (payload.pendingControlEvent() != null) {
            handlePendingControl(payload.pendingControlEvent(), payload.pendingControlEvent().controlType());
        }
        List<UiMessage> recentChats = payload.recentChats() == null ? List.of() : payload.recentChats().stream()
                .map(chat -> new UiMessage(chat.senderSessionId(), chat.content(), false))
                .toList();
        store.replaceChats(recentChats);
        if (payload.room().status() == RoomStatus.FINISHED) {
            store.resultVisibleProperty().set(true);
            sessionStore.clearRoomContext();
            return;
        }
        saveSessionSnapshot();
    }

    private void handleGameOver(GameOverPayload payload) {
        if (payload == null) {
            return;
        }
        store.roomStatusProperty().set(RoomStatus.FINISHED);
        store.resultVisibleProperty().set(true);
        store.finishReasonProperty().set(payload.endReason());
        store.winnerSideProperty().set(payload.winnerSide());
        if (payload.finalFen() != null) {
            Side turn = payload.finalFen().endsWith(" w") ? Side.RED : Side.BLACK;
            store.updateBoard(payload.finalFen(), turn,
                    payload.finalMoveNo() == null ? store.moveNo() : payload.finalMoveNo(),
                    store.redTimeLeftMsProperty().get(), store.blackTimeLeftMsProperty().get());
        } else if (payload.finalMoveNo() != null) {
            store.moveNoProperty().set(payload.finalMoveNo());
        }
        store.pendingControlProperty().set(null);
        store.actionLockedProperty().set(false);
        sessionStore.clearRoomContext();
    }

    private String controlTypeLabel(ControlType controlType) {
        return controlType == ControlType.UNDO ? "悔棋" : "求和";
    }

    private String formatMoveSummary(MoveSummary summary) {
        String side = summary.mover() == Side.RED ? "红方" : "黑方";
        String capture = summary.capturedPiece() == null || summary.capturedPiece().isBlank()
                ? ""
                : "，吃掉 " + summary.capturedPiece();
        return "第 " + summary.moveNo() + " 手 " + side + " " + summary.from() + " -> " + summary.to() + capture;
    }

    private Side inferMover(int moveNo) {
        return moveNo % 2 == 1 ? Side.RED : Side.BLACK;
    }

    private final class WsListener implements GameWebSocketService.Listener {
        @Override
        public void onAuthenticated(String sessionId, String resumeToken) {
            Platform.runLater(() -> {
                store.sessionIdProperty().set(sessionId);
                store.setConnected(true, "已连接");
                String roomId = store.roomId() != null ? store.roomId() : currentStoredSession.map(StoredSession::roomId).orElse(null);
                Side persistedSide = store.mySide() != null ? store.mySide() : currentStoredSession.map(StoredSession::mySide).orElse(null);
                sessionStore.save(sessionId, resumeToken, roomId, store.moveNo(), persistedSide);
                currentStoredSession = sessionStore.load();
                if (roomId != null) {
                    store.showScreen(Screen.GAME);
                    store.mySideProperty().set(persistedSide);
                    store.reconnectingProperty().set(true);
                    store.reconnectDeadlineMsProperty().set(Instant.now().plusSeconds(30).toEpochMilli());
                    store.connectionStatusProperty().set("连接恢复成功，正在同步局面");
                    try {
                        int lastKnownMoveNo = Math.max(store.moveNo(), currentStoredSession.map(StoredSession::lastKnownMoveNo).orElse(0));
                        webSocketService.send(MessageType.SNAPSHOT_SYNC, roomId, new SnapshotSyncPayload(lastKnownMoveNo));
                    } catch (Exception e) {
                        store.showNotice("发送快照同步失败: " + rootMessage(e));
                    }
                } else if (store.matchingProperty().get()) {
                    store.showScreen(Screen.MATCHING);
                } else {
                    store.showScreen(Screen.LOBBY);
                }
            });
        }

        @Override
        public void onMessage(ServerEnvelope envelope) {
            Platform.runLater(() -> handleEnvelope(envelope));
        }

        @Override
        public void onDisconnected(int code, String reason, boolean remote, boolean expected) {
            Platform.runLater(() -> {
                store.setConnected(false, "连接已断开");
                if (!expected) {
                    beginReconnectLoop();
                }
            });
        }

        @Override
        public void onError(String message) {
            Platform.runLater(() -> store.showNotice("网络异常: " + message));
        }
    }

    private static final class MessageCell extends ListCell<UiMessage> {
        @Override
        protected void updateItem(UiMessage item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(item.system() ? "[系统] " + item.content() : "[" + item.sender() + "] " + item.content());
        }
    }

    private static final class MoveSummaryCell extends ListCell<MoveSummary> {
        @Override
        protected void updateItem(MoveSummary item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            String mover = item.mover() == Side.RED ? "红" : "黑";
            String capture = item.capturedPiece() == null || item.capturedPiece().isBlank() ? "" : " 吃 " + item.capturedPiece();
            setText(item.moveNo() + ". " + mover + " " + item.from() + " -> " + item.to() + capture);
        }
    }
}
