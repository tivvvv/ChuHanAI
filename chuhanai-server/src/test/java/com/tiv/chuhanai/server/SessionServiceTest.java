package com.tiv.chuhanai.server;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionServiceTest {

    @Test
    void shouldResumeExistingSessionWithMatchingToken() {
        SessionService sessionService = new SessionService();
        EmbeddedChannel firstChannel = new EmbeddedChannel();

        AuthenticatedSession created = sessionService.connect(
                new ConnectPayload(ProtocolModels.PROTOCOL_VERSION, "test-client", null, null),
                firstChannel
        );

        EmbeddedChannel resumedChannel = new EmbeddedChannel();
        AuthenticatedSession resumed = sessionService.connect(
                new ConnectPayload(ProtocolModels.PROTOCOL_VERSION, "test-client", created.sessionId(), created.resumeToken()),
                resumedChannel
        );

        assertTrue(resumed.resumed());
        assertEquals(created.sessionId(), resumed.sessionId());
        assertEquals(created.resumeToken(), resumed.resumeToken());
        assertEquals(created.sessionId(), sessionService.sessionId(resumedChannel).orElseThrow());
        assertFalse(firstChannel.isActive());
    }

    @Test
    void shouldRejectResumeWhenTokenDoesNotMatch() {
        SessionService sessionService = new SessionService();
        EmbeddedChannel firstChannel = new EmbeddedChannel();

        AuthenticatedSession created = sessionService.connect(
                new ConnectPayload(ProtocolModels.PROTOCOL_VERSION, "test-client", null, null),
                firstChannel
        );

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                sessionService.connect(
                        new ConnectPayload(ProtocolModels.PROTOCOL_VERSION, "test-client", created.sessionId(), "bad-token"),
                        new EmbeddedChannel()
                ));

        assertEquals("会话无效", exception.getMessage());
    }
}
