package com.chat.netty;

import com.chat.auth.AuthService;
import com.chat.auth.JwtUtil;
import com.chat.model.Message;
import com.chat.neurodb.NeuroDbClient;
import com.chat.protocol.*;
import com.chat.util.SnowflakeId;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * WebSocket 消息处理：首包必须为 auth（携带 JWT），通过后注册 Channel；
 * 之后可处理 chat（存 NeuroDB 并广播）、sync（按 lastTimestamp 拉取增量）。
 */
public class ChatWebSocketHandler extends SimpleChannelInboundHandler<Object> {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final Gson GSON = new Gson();

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final NeuroDbClient neuroDb;
    private final ChannelRegistry registry;
    private final SnowflakeId snowflakeId = new SnowflakeId();

    /** 当前连接对应的 userId，未认证为 null */
    private String currentUserId;

    public ChatWebSocketHandler(AuthService authService, JwtUtil jwtUtil, NeuroDbClient neuroDb, ChannelRegistry registry) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.neuroDb = neuroDb;
        this.registry = registry;
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
        if (msg instanceof FullHttpRequest) {
            handleHttpRequest(ctx, (FullHttpRequest) msg);
            return;
        }
        if (!(msg instanceof WebSocketFrame)) return;
        WebSocketFrame frame = (WebSocketFrame) msg;
        if (!(frame instanceof TextWebSocketFrame)) {
            ctx.close();
            return;
        }
        String text = ((TextWebSocketFrame) frame).text();
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
                // 未认证：只接受 auth
                if ("auth".equals(type)) {
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

            switch (type) {
                case "chat":
                    handleChat(ctx, map);
                    break;
                case "sync":
                    handleSync(ctx, map);
                    break;
                case "RECALL":
                    handleRecall(ctx, map);
                    break;
                default:
                    sendError(ctx, "Unknown type: " + type);
            }
        } catch (Exception e) {
            log.warn("handle message failed: {}", e.getMessage());
            sendError(ctx, e.getMessage());
        }
    }

    private void handleChat(ChannelHandlerContext ctx, Map<String, Object> map) {
        String content = (String) map.get("content");
        if (content == null) content = "";
        long ts = System.currentTimeMillis();
        int messageId = snowflakeId.nextId();
        Message msg = new Message(messageId, currentUserId, content, ts);
        Object rtId = map.get("replyToId");
        if (rtId != null) msg.setReplyToId(((Number) rtId).longValue());
        String rtUser = (String) map.get("replyToUser");
        if (rtUser != null) msg.setReplyToUser(rtUser);
        String rtContent = (String) map.get("replyToContent");
        if (rtContent != null) msg.setReplyToContent(rtContent);
        @SuppressWarnings("unchecked")
        List<String> mentions = (List<String>) map.get("mentions");
        if (mentions != null) msg.setMentions(mentions);
        String value = GSON.toJson(msg);
        try {
            neuroDb.put(messageId, value);
        } catch (IOException e) {
            log.error("NeuroDB put failed: {}", e.getMessage());
            sendError(ctx, "Storage failed");
            return;
        }
        ChatMessagePacket packet = new ChatMessagePacket();
        packet.messageId = messageId;
        packet.senderId = currentUserId;
        packet.content = content;
        packet.timestamp = ts;
        packet.replyToId = msg.getReplyToId();
        packet.replyToUser = msg.getReplyToUser();
        packet.replyToContent = msg.getReplyToContent();
        packet.mentions = msg.getMentions();
        String json = GSON.toJson(packet);
        registry.broadcast(currentUserId, json);
        ctx.writeAndFlush(new TextWebSocketFrame(json));
    }

    private static final long RECALL_MAX_MS = 2 * 60 * 1000L;

    private void handleRecall(ChannelHandlerContext ctx, Map<String, Object> map) {
        Object midObj = map.get("messageId");
        if (midObj == null) { sendError(ctx, "messageId required"); return; }
        int messageId = ((Number) midObj).intValue();
        String json;
        try {
            json = neuroDb.get(messageId);
        } catch (IOException e) {
            sendError(ctx, "Recall failed"); return;
        }
        if (json == null || json.isBlank()) { sendError(ctx, "Message not found"); return; }
        Message msg = GSON.fromJson(json, Message.class);
        if (msg == null || !currentUserId.equals(msg.getSenderId())) { sendError(ctx, "Not your message"); return; }
        if (msg.isRecalled()) { sendError(ctx, "Already recalled"); return; }
        long age = System.currentTimeMillis() - msg.getTimestamp();
        if (age > RECALL_MAX_MS) { sendError(ctx, "Recall timeout (2 min)"); return; }
        msg.setContent("");
        msg.setRecalled(true);
        try {
            neuroDb.put(messageId, GSON.toJson(msg));
        } catch (IOException e) {
            sendError(ctx, "Recall failed"); return;
        }
        RecallPacket recall = new RecallPacket();
        recall.messageId = messageId;
        recall.senderId = currentUserId;
        String recallJson = GSON.toJson(recall);
        registry.broadcast(null, recallJson);
        ctx.writeAndFlush(new TextWebSocketFrame(recallJson));
    }

    /** 全量拉取历史时从 0 开始扫，非消息记录（如用户）会在解析时被过滤掉。 */
    private static final int SYNC_ALL_START_KEY = 0;

    private void handleSync(ChannelHandlerContext ctx, Map<String, Object> map) {
        Object tsObj = map.get("lastTimestamp");
        long lastTs = tsObj instanceof Number ? ((Number) tsObj).longValue() : 0L;
        int startKey = lastTs <= 0 ? SYNC_ALL_START_KEY : SnowflakeId.timestampToStartKey(lastTs);
        int endKey = Integer.MAX_VALUE;
        List<ChatMessagePacket> list = new ArrayList<>();
        try {
            for (NeuroDbClient.ScanRecord rec : neuroDb.scan(startKey, endKey)) {
                Message m = GSON.fromJson(rec.value, Message.class);
                if (m == null || m.getSenderId() == null) continue;
                if (m.getTimestamp() <= lastTs) continue;
                ChatMessagePacket p = new ChatMessagePacket();
                p.messageId = m.getMessageId();
                p.senderId = m.getSenderId();
                p.content = m.isRecalled() ? "" : (m.getContent() != null ? m.getContent() : "");
                p.timestamp = m.getTimestamp();
                p.isRecalled = m.isRecalled();
                p.replyToId = m.getReplyToId();
                p.replyToUser = m.getReplyToUser();
                p.replyToContent = m.getReplyToContent();
                p.mentions = m.getMentions();
                list.add(p);
            }
        } catch (IOException e) {
            log.warn("NeuroDB scan failed: {}", e.getMessage());
            sendError(ctx, "Sync failed");
            return;
        }
        list.sort(Comparator.comparingLong(p -> p.timestamp));
        SyncResultPacket result = new SyncResultPacket();
        result.messages = list;
        send(ctx, result);
    }

    private void send(ChannelHandlerContext ctx, Object packet) {
        ctx.writeAndFlush(new TextWebSocketFrame(GSON.toJson(packet)));
    }

    private void sendError(ChannelHandlerContext ctx, String reason) {
        ErrorPacket err = new ErrorPacket();
        err.reason = reason;
        send(ctx, err);
    }

    /** 处理 HTTP：/api/login, /api/register, /api/admin/* */
    private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        String uri = req.uri();
        if (uri == null) uri = "";
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;

        if ("/api/login".equals(path)) {
            handleLogin(ctx, req);
            return;
        }
        if ("/api/register".equals(path)) {
            handleRegister(ctx, req);
            return;
        }
        if ("/api/admin/users".equals(path)) {
            handleAdminUsers(ctx, req);
            return;
        }
        if ("/api/admin/approve".equals(path)) {
            handleAdminApprove(ctx, req);
            return;
        }
        if ("/api/admin/reject".equals(path)) {
            handleAdminReject(ctx, req);
            return;
        }
        if ("/api/online".equals(path)) {
            handleOnline(ctx, req);
            return;
        }
        ctx.close();
    }

    private void handleOnline(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != io.netty.handler.codec.http.HttpMethod.GET) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        req.release();
        if (token == null || jwtUtil.parseUserId(token) == null) {
            sendHttpJson(ctx, HttpResponseStatus.UNAUTHORIZED, Map.of("error", "Login required"));
            return;
        }
        List<String> users = registry.getOnlineUserIds();
        sendHttpJson(ctx, HttpResponseStatus.OK, Map.of("users", users));
    }

    private void handleLogin(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != io.netty.handler.codec.http.HttpMethod.POST) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        req.release();
        @SuppressWarnings("unchecked")
        Map<String, String> map = GSON.fromJson(body, Map.class);
        if (map == null) {
            sendHttpJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "Invalid JSON"));
            return;
        }
        String username = map.get("username");
        if (username == null) username = map.get("userId");
        String password = map.get("password");
        String token = authService.login(username, password);
        if (token == null) {
            sendHttpJson(ctx, HttpResponseStatus.UNAUTHORIZED, Map.of("error", "Invalid credentials or not approved"));
            return;
        }
        sendHttpJson(ctx, HttpResponseStatus.OK, Map.of("token", token));
    }

    private void handleRegister(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != io.netty.handler.codec.http.HttpMethod.POST) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        req.release();
        @SuppressWarnings("unchecked")
        Map<String, String> map = GSON.fromJson(body, Map.class);
        if (map == null) {
            sendHttpJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "Invalid JSON"));
            return;
        }
        String username = map.get("username");
        String password = map.get("password");
        try {
            String err = authService.register(username, password);
            if (err != null) {
                sendHttpJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", err));
                return;
            }
            sendHttpJson(ctx, HttpResponseStatus.OK, Map.of("ok", true, "message", "申请已提交，等待管理员审核"));
        } catch (IOException e) {
            log.warn("Register failed: {}", e.getMessage());
            sendHttpJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private String bearerToken(FullHttpRequest req) {
        CharSequence auth = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth == null) return null;
        String s = auth.toString();
        if (s.startsWith("Bearer ")) return s.substring(7).trim();
        return null;
    }

    private void handleAdminUsers(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != io.netty.handler.codec.http.HttpMethod.GET) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        req.release();
        if (token == null || !"ADMIN".equals(jwtUtil.parseRole(token))) {
            sendHttpJson(ctx, HttpResponseStatus.FORBIDDEN, Map.of("error", "Admin required"));
            return;
        }
        try {
            List<Long> pendingIds = authService.getPendingUserIds();
            List<Map<String, Object>> users = new ArrayList<>();
            for (Long id : pendingIds) {
                com.chat.model.User u = authService.getUserByUserId(id.intValue());
                if (u != null) {
                    users.add(Map.<String, Object>of(
                            "userId", u.getUserId(),
                            "username", u.getUsername() != null ? u.getUsername() : ""
                    ));
                }
            }
            sendHttpJson(ctx, HttpResponseStatus.OK, Map.of("users", users));
        } catch (IOException e) {
            log.warn("Admin users failed: {}", e.getMessage());
            sendHttpJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private void handleAdminApprove(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != io.netty.handler.codec.http.HttpMethod.POST) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        if (token == null || !"ADMIN".equals(jwtUtil.parseRole(token))) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.FORBIDDEN, Map.of("error", "Admin required"));
            return;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        req.release();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = GSON.fromJson(body, Map.class);
        if (map == null || !map.containsKey("userId")) {
            sendHttpJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "userId required"));
            return;
        }
        int userId = ((Number) map.get("userId")).intValue();
        try {
            authService.approve(userId);
            sendHttpJson(ctx, HttpResponseStatus.OK, Map.of("ok", true));
        } catch (IOException e) {
            log.warn("Approve failed: {}", e.getMessage());
            sendHttpJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private void handleAdminReject(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != io.netty.handler.codec.http.HttpMethod.POST) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        if (token == null || !"ADMIN".equals(jwtUtil.parseRole(token))) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.FORBIDDEN, Map.of("error", "Admin required"));
            return;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        req.release();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = GSON.fromJson(body, Map.class);
        if (map == null || !map.containsKey("userId")) {
            sendHttpJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "userId required"));
            return;
        }
        int userId = ((Number) map.get("userId")).intValue();
        try {
            authService.reject(userId);
            sendHttpJson(ctx, HttpResponseStatus.OK, Map.of("ok", true));
        } catch (IOException e) {
            log.warn("Reject failed: {}", e.getMessage());
            sendHttpJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private void sendHttpJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object body) {
        String json = GSON.toJson(body);
        io.netty.buffer.ByteBuf buf = Unpooled.copiedBuffer(json, CharsetUtil.UTF_8);
        io.netty.handler.codec.http.FullHttpResponse resp = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, buf);
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
