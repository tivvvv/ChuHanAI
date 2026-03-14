package com.tiv.chuhanai.server;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Component
class NettyGatewayServer implements SmartLifecycle {
    private static final String WEBSOCKET_PATH = "/ws/v1/game";

    private final ObjectMapper objectMapper;
    private final SessionService sessionService;
    private final GameCoordinator gameCoordinator;
    private final int nettyPort;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private volatile boolean running;

    NettyGatewayServer(ObjectMapper objectMapper,
                       SessionService sessionService,
                       GameCoordinator gameCoordinator,
                       @Value("${netty.port:10090}") int nettyPort) {
        this.objectMapper = objectMapper;
        this.sessionService = sessionService;
        this.gameCoordinator = gameCoordinator;
        this.nettyPort = nettyPort;
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline pipeline = ch.pipeline();
                            pipeline.addLast(new HttpServerCodec());
                            pipeline.addLast(new HttpObjectAggregator(64 * 1024));
                            pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true, 64 * 1024));
                            pipeline.addLast(new IdleStateHandler(15, 0, 0));
                            pipeline.addLast(new GatewayChannelHandler(objectMapper, sessionService, gameCoordinator));
                        }
                    });
            ChannelFuture future = bootstrap.bind(nettyPort).sync();
            serverChannel = future.channel();
            running = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Netty 服务启动被中断", e);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private static final class GatewayChannelHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

        private final ObjectMapper objectMapper;
        private final SessionService sessionService;
        private final GameCoordinator gameCoordinator;

        private GatewayChannelHandler(ObjectMapper objectMapper, SessionService sessionService, GameCoordinator gameCoordinator) {
            this.objectMapper = objectMapper;
            this.sessionService = sessionService;
            this.gameCoordinator = gameCoordinator;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
            String payload = frame.text();
            InboundMessage message;
            try {
                message = objectMapper.readValue(payload, InboundMessage.class);
            } catch (Exception e) {
                write(ctx.channel(), OutboundMessage.failure(MessageType.PONG, null, null,
                        "INVALID_REQUEST", "非法 JSON 报文", false, null));
                return;
            }

            String sessionId = sessionService.sessionId(ctx.channel()).orElse(null);
            if (sessionId == null) {
                handleConnect(ctx, message);
                return;
            }
            if (message.seq() != null && !sessionService.acceptSeq(sessionId, message.seq())) {
                write(ctx.channel(), OutboundMessage.failure(resolveErrorType(message.type()), message.roomId(), message.msgId(),
                        "INVALID_REQUEST", "seq 必须严格递增", false, null));
                return;
            }
            sessionService.touch(sessionId);
            gameCoordinator.handle(sessionId, message);
        }

        private void handleConnect(ChannelHandlerContext ctx, InboundMessage message) {
            if (message.type() != MessageType.CONNECT) {
                write(ctx.channel(), OutboundMessage.failure(MessageType.CONNECTED, null, message.msgId(),
                        "UNAUTHORIZED", "连接建立后必须先发送 CONNECT", false, null));
                ctx.close();
                return;
            }
            try {
                ConnectPayload payload = objectMapper.convertValue(message.payload(), ConnectPayload.class);
                AuthenticatedSession authenticatedSession = sessionService.connect(payload, ctx.channel());
                gameCoordinator.onConnected(authenticatedSession.sessionId());
                write(ctx.channel(), OutboundMessage.success(MessageType.CONNECTED, null, message.msgId(),
                        new ConnectedPayload(
                                authenticatedSession.sessionId(),
                                authenticatedSession.resumeToken(),
                                authenticatedSession.resumed(),
                                ProtocolModels.PROTOCOL_VERSION,
                                "Asia/Shanghai"
                        )));
            } catch (IllegalArgumentException exception) {
                write(ctx.channel(), OutboundMessage.failure(MessageType.CONNECTED, null, message.msgId(),
                        "UNAUTHORIZED", exception.getMessage(), false, null));
                ctx.close();
            }
        }

        private void write(Channel channel, OutboundMessage message) {
            try {
                channel.writeAndFlush(new TextWebSocketFrame(objectMapper.writeValueAsString(message)));
            } catch (JsonProcessingException e) {
                channel.writeAndFlush(new TextWebSocketFrame("{\"type\":\"PONG\",\"success\":false}"));
            }
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

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent idleStateEvent && idleStateEvent.state() == IdleState.READER_IDLE) {
                ctx.close();
                return;
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            sessionService.unbind(ctx.channel()).ifPresent(gameCoordinator::onDisconnected);
            super.channelInactive(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
