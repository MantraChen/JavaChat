package com.chat.network.netty;

import com.chat.auth.AuthService;
import com.chat.auth.JwtUtil;
import com.chat.core.ProtocolConsts;
import com.chat.neurodb.NeuroDbClient;
import com.chat.redis.RedisChatBus;
import com.chat.service.MessageService;
import com.chat.network.http.HttpDispatcherHandler;
import com.chat.network.ws.ChatMessageHandler;
import com.chat.network.ws.RecallMessageHandler;
import com.chat.network.ws.SyncMessageHandler;
import com.chat.network.ws.TypingMessageHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketFrameAggregator;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

import java.util.Map;
import java.util.HashMap;

/**
 * Pipeline: HTTP 编解码 -> 聚合 -> HttpDispatcher(/api、/files) -> WebSocket 握手(/ws) -> 静态页 -> WS 业务（策略分发）。
 */
public class ChatServerInitializer extends ChannelInitializer<SocketChannel> {
    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final NeuroDbClient neuroDb;
    private final ChannelRegistry registry;
    private final RedisChatBus redisBus;
    private final String uploadDir;

    public ChatServerInitializer(AuthService authService, JwtUtil jwtUtil,
                                 NeuroDbClient neuroDb, ChannelRegistry registry, RedisChatBus redisBus, String uploadDir) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.neuroDb = neuroDb;
        this.registry = registry;
        this.redisBus = redisBus;
        this.uploadDir = uploadDir != null ? uploadDir : "upload";
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        p.addLast(new HttpServerCodec());
        p.addLast(new HttpObjectAggregator(10 * 1024 * 1024)); // 10MB for HTTP/WebSocket (Base64 图片等)
        p.addLast(new ChunkedWriteHandler());
        p.addLast(new HttpDispatcherHandler(authService, jwtUtil, registry, uploadDir));

        WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath("/ws")
                .maxFramePayloadLength(10 * 1024 * 1024)
                .build();
        p.addLast(new WebSocketServerProtocolHandler(wsConfig));
        p.addLast(new WebSocketFrameAggregator(10 * 1024 * 1024));
        p.addLast(new HttpStaticHandler());
        p.addLast(new ChatWebSocketHandler(authService, jwtUtil, registry, buildHandlers()));
    }

    private Map<String, com.chat.network.ws.MessageHandler> buildHandlers() {
        MessageService messageService = new MessageService(neuroDb);
        RedisChatBus bus = redisBus != null ? redisBus : new RedisChatBus("", 6379, registry);

        Map<String, com.chat.network.ws.MessageHandler> map = new HashMap<>();
        map.put(ProtocolConsts.TYPE_CHAT, new ChatMessageHandler(authService, messageService, registry, bus));
        map.put(ProtocolConsts.TYPE_SYNC, new SyncMessageHandler(messageService));
        map.put(ProtocolConsts.TYPE_RECALL, new RecallMessageHandler(messageService, registry, bus));
        map.put(ProtocolConsts.TYPE_TYPING, new TypingMessageHandler(registry, bus));
        return map;
    }
}
