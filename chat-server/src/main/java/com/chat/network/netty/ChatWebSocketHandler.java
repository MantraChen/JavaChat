package com.chat.network.netty;

import com.chat.auth.JwtUtil;
import com.chat.core.ProtocolConsts;
import com.chat.network.ws.HandlerException;
import com.chat.network.ws.MessageHandler;
import com.chat.protocol.AuthFailPacket;
import com.chat.protocol.AuthOkPacket;
import com.chat.protocol.ErrorPacket;
import com.google.gson.Gson;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonSyntaxException;

import java.util.Map;

/**
 * WebSocket 消息处理：首包必须为 auth（携带 JWT），通过后注册 Channel；
 * 之后按 type 分发到各 MessageHandler（chat / sync / RECALL / typing）。
 * HTTP 已由 HttpDispatcherHandler 处理，此处仅对误入的 HTTP 回 404。
 */
public class ChatWebSocketHandler extends SimpleChannelInboundHandler<Object> {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final Gson GSON = new Gson();

    private final JwtUtil jwtUtil;
    private final ChannelRegistry registry;
    private final Map<String, MessageHandler> handlers;

    /** 当前连接对应的 userId，未认证为 null */
    private String currentUserId;

    public ChatWebSocketHandler(JwtUtil jwtUtil, ChannelRegistry registry,
                                Map<String, MessageHandler> handlers) {
        this.jwtUtil = jwtUtil;
        this.registry = registry;
        this.handlers = handlers;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        log.debug("channel active: {}", ctx.channel().id());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (currentUserId != null) {
            registry.unregister(currentUserId);
            log.info("user {} disconnected", currentUserId);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof FullHttpRequest req) {
            req.release();
            sendHttpNotFound(ctx);
            return;
        }
        if (!(msg instanceof WebSocketFrame frame)) return;
        if (!(frame instanceof TextWebSocketFrame textFrame)) {
            ctx.close();
            return;
        }
        String text = textFrame.text();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = GSON.fromJson(text, Map.class);
            if (map == null) {
                sendError(ctx, "Invalid JSON");
                return;
            }
            String type = (String) map.get("type");
            if (type == null) type = "";

            if (currentUserId == null) {
                if (ProtocolConsts.TYPE_AUTH.equals(type)) {
                    String token = (String) map.get("token");
                    String userId = jwtUtil.parseUserId(token);
                    if (userId == null) {
                        AuthFailPacket fail = new AuthFailPacket();
                        fail.reason = "Invalid or expired token";
                        send(ctx, fail);
                        ctx.close();
                        return;
                    }
                    currentUserId = userId;
                    registry.register(userId, ctx.channel());
                    AuthOkPacket ok = new AuthOkPacket();
                    ok.userId = userId;
                    send(ctx, ok);
                    log.info("user {} authenticated", userId);
                } else {
                    sendError(ctx, "Auth required");
                    ctx.close();
                }
                return;
            }

            MessageHandler handler = handlers.get(type);
            if (handler != null) {
                try {
                    handler.handle(ctx, map, currentUserId);
                } catch (HandlerException e) {
                    sendError(ctx, e.getMessage());
                    if ("账号已被封禁".equals(e.getMessage())) {
                        ctx.close();
                    }
                }
            } else {
                sendError(ctx, "Unknown type: " + type);
            }
        } catch (JsonSyntaxException e) {
            log.warn("handle message failed: {}", e.getMessage());
            sendError(ctx, e.getMessage());
        }
    }

    private void send(ChannelHandlerContext ctx, Object packet) {
        ctx.writeAndFlush(new TextWebSocketFrame(GSON.toJson(packet)));
    }

    private void sendError(ChannelHandlerContext ctx, String reason) {
        ErrorPacket err = new ErrorPacket();
        err.reason = reason;
        send(ctx, err);
    }

    private void sendHttpNotFound(ChannelHandlerContext ctx) {
        io.netty.buffer.ByteBuf buf = Unpooled.copiedBuffer("{\"error\":\"Not found\"}", CharsetUtil.UTF_8);
        io.netty.handler.codec.http.FullHttpResponse resp = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, buf);
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
                .set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("exception: {}", cause.getMessage());
        ctx.close();
    }
}
