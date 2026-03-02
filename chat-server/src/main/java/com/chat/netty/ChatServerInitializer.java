package com.chat.netty;

import com.chat.auth.AuthService;
import com.chat.auth.JwtUtil;
import com.chat.neurodb.NeuroDbClient;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
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

    public ChatServerInitializer(AuthService authService, JwtUtil jwtUtil,
                                 NeuroDbClient neuroDb, ChannelRegistry registry) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.neuroDb = neuroDb;
        this.registry = registry;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        p.addLast(new HttpServerCodec());
        p.addLast(new HttpObjectAggregator(65536));
        p.addLast(new ChunkedWriteHandler());
        p.addLast(new WebSocketServerProtocolHandler("/ws", null, true));
        p.addLast(new ChatWebSocketHandler(authService, jwtUtil, neuroDb, registry));
    }
}
