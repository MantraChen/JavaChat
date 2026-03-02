package com.chat.netty;

import com.chat.auth.AuthService;
import com.google.gson.Gson;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.util.Map;

/**
 * 处理 HTTP POST /api/login：body 为 JSON {"userId":"A","password":"xxx"}，
 * 成功返回 {"token":"jwt"}，失败返回 401 及 {"error":"..."}。
 */
public class HttpLoginHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final String LOGIN_PATH = "/api/login";
    private static final Gson GSON = new Gson();

    private final AuthService authService;

    public HttpLoginHandler(AuthService authService) {
        this.authService = authService;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!req.uri().startsWith(LOGIN_PATH)) {
            ctx.fireChannelRead(req.retain());
            return;
        }
        if (req.method() != HttpMethod.POST) {
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        @SuppressWarnings("unchecked")
        Map<String, String> map = GSON.fromJson(body, Map.class);
        if (map == null) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "Invalid JSON"));
            return;
        }
        String userId = map.get("userId");
        String password = map.get("password");
        String token = authService.login(userId, password);
        if (token == null) {
            sendJson(ctx, HttpResponseStatus.UNAUTHORIZED, Map.of("error", "Invalid credentials"));
            return;
        }
        sendJson(ctx, HttpResponseStatus.OK, Map.of("token", token));
    }

    private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object body) {
        String json = GSON.toJson(body);
        ByteBuf buf = Unpooled.copiedBuffer(json, CharsetUtil.UTF_8);
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
                .set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }
}
