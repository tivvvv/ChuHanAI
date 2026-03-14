package com.tiv.chuhanai.client.store;

import com.tiv.chuhanai.client.net.ClientProtocol.Side;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

public class SessionStore {
    private static final String SESSION_ID = "sessionId";
    private static final String RESUME_TOKEN = "resumeToken";
    private static final String ROOM_ID = "roomId";
    private static final String MOVE_NO = "moveNo";
    private static final String MY_SIDE = "mySide";

    private final Path storePath;

    public SessionStore() {
        this(Path.of(System.getProperty("user.home"), ".chuhanai-client", "session.properties"));
    }

    SessionStore(Path storePath) {
        this.storePath = storePath;
    }

    public Optional<StoredSession> load() {
        if (!Files.exists(storePath)) {
            return Optional.empty();
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(storePath)) {
            properties.load(in);
            String sessionId = properties.getProperty(SESSION_ID);
            String resumeToken = properties.getProperty(RESUME_TOKEN);
            if (isBlank(sessionId) || isBlank(resumeToken)) {
                return Optional.empty();
            }
            return Optional.of(new StoredSession(
                    sessionId,
                    resumeToken,
                    emptyToNull(properties.getProperty(ROOM_ID)),
                    parseInt(properties.getProperty(MOVE_NO)),
                    parseSide(properties.getProperty(MY_SIDE))
            ));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void save(String sessionId, String resumeToken, String roomId, int moveNo, Side mySide) {
        Properties properties = new Properties();
        properties.setProperty(SESSION_ID, sessionId);
        properties.setProperty(RESUME_TOKEN, resumeToken);
        if (!isBlank(roomId)) {
            properties.setProperty(ROOM_ID, roomId);
        }
        properties.setProperty(MOVE_NO, Integer.toString(Math.max(moveNo, 0)));
        if (mySide != null) {
            properties.setProperty(MY_SIDE, mySide.name());
        }
        try {
            Files.createDirectories(storePath.getParent());
            try (OutputStream out = Files.newOutputStream(storePath)) {
                properties.store(out, "ChuHanAI client session");
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist client session", e);
        }
    }

    public void clearRoomContext() {
        Optional<StoredSession> session = load();
        if (session.isEmpty()) {
            return;
        }
        save(session.get().sessionId(), session.get().resumeToken(), null, 0, session.get().mySide());
    }

    public void clearAll() {
        try {
            Files.deleteIfExists(storePath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to clear session store", e);
        }
    }

    private static int parseInt(String value) {
        if (isBlank(value)) {
            return 0;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String emptyToNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static Side parseSide(String value) {
        if (isBlank(value)) {
            return null;
        }
        try {
            return Side.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record StoredSession(
            String sessionId,
            String resumeToken,
            String roomId,
            int lastKnownMoveNo,
            Side mySide
    ) {
    }
}
