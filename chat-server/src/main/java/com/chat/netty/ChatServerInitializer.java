package com.chat.netty;

import com.chat.auth.AuthService;
import com.chat.auth.JwtUtil;
import com.chat.neurodb.NeuroDbClient;
import com.chat.redis.RedisChatBus;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

/**
 * Pipeline: HTTP 编解码 -> 聚合 -> WebSocket 握手(/ws) -> 业务（登录 / 聊天 / SYNC）。
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
        WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath("/ws")
                .maxFramePayloadLength(10 * 1024 * 1024)
                .build();
        p.addLast(new WebSocketServerProtocolHandler(wsConfig));
        p.addLast(new HttpStaticHandler());
        p.addLast(new ChatWebSocketHandler(authService, jwtUtil, neuroDb, registry, redisBus, uploadDir));
    }
}
