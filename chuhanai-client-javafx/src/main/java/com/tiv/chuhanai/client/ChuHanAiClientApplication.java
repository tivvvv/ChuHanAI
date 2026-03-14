package com.tiv.chuhanai.client;

import com.tiv.chuhanai.client.config.ClientConfig;
import com.tiv.chuhanai.client.net.GameWebSocketService;
import com.tiv.chuhanai.client.net.HealthApiClient;
import com.tiv.chuhanai.client.store.ClientStore;
import com.tiv.chuhanai.client.store.SessionStore;
import com.tiv.chuhanai.client.ui.AppShell;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class ChuHanAiClientApplication extends Application {
    private GameWebSocketService webSocketService;

    @Override
    public void start(Stage primaryStage) {
        ClientConfig config = ClientConfig.load();
        ClientStore store = new ClientStore();
        SessionStore sessionStore = new SessionStore();
        webSocketService = new GameWebSocketService(config);
        HealthApiClient healthApiClient = new HealthApiClient(config);
        AppShell appShell = new AppShell(config, store, sessionStore, webSocketService, healthApiClient);

        Scene scene = new Scene(appShell, 1360, 860);
        primaryStage.setTitle("ChuHanAI 客户端");
        primaryStage.setScene(scene);
        primaryStage.setMinWidth(1180);
        primaryStage.setMinHeight(760);
        primaryStage.show();

        appShell.bootstrap();
        primaryStage.setOnCloseRequest(event -> webSocketService.shutdown());
    }

    @Override
    public void stop() {
        if (webSocketService != null) {
            webSocketService.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
