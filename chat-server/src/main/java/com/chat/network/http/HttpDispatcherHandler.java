    package com.chat.network.http;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chat.auth.AuthService;
import com.chat.auth.JwtUtil;
import static com.chat.core.ProtocolConsts.API_ADMIN_ACTION;
import static com.chat.core.ProtocolConsts.API_ADMIN_ALL_USERS;
import static com.chat.core.ProtocolConsts.API_ADMIN_APPROVE;
import static com.chat.core.ProtocolConsts.API_ADMIN_BROADCAST;
import static com.chat.core.ProtocolConsts.API_ADMIN_MESSAGE_RECALL;
import static com.chat.core.ProtocolConsts.API_ADMIN_REJECT;
import static com.chat.core.ProtocolConsts.API_ADMIN_USERS;
import static com.chat.core.ProtocolConsts.API_LOGIN;
import static com.chat.core.ProtocolConsts.API_ONLINE;
import static com.chat.core.ProtocolConsts.API_REGISTER;
import static com.chat.core.ProtocolConsts.API_UPLOAD;
import static com.chat.core.ProtocolConsts.API_USERS;
import static com.chat.core.ProtocolConsts.API_USER_PROFILE;
import static com.chat.core.ProtocolConsts.FILES_PREFIX;
import static com.chat.core.ProtocolConsts.SENDER_SYSTEM;
import static com.chat.core.ProtocolConsts.TYPE_RECALL;
import static com.chat.core.ProtocolConsts.TYPE_SYSTEM;
import com.chat.model.User;
import com.chat.network.netty.ChannelRegistry;
import com.chat.service.FileStorageService;
import com.google.gson.Gson;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.CharsetUtil;

/**
 * HTTP API dispatcher using route table pattern.
 * Handles /api/* and /files/*; passes other requests downstream.
 */
public class HttpDispatcherHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(HttpDispatcherHandler.class);
    private static final Gson GSON = new Gson();

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final ChannelRegistry registry;
    private final FileStorageService fileStorage;
    private final Map<String, BiConsumer<ChannelHandlerContext, FullHttpRequest>> routes;

    public HttpDispatcherHandler(AuthService authService, JwtUtil jwtUtil,
                                 ChannelRegistry registry, String uploadDir) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.registry = registry;
        this.fileStorage = new FileStorageService(uploadDir);
        this.routes = buildRoutes();
    }

    private Map<String, BiConsumer<ChannelHandlerContext, FullHttpRequest>> buildRoutes() {
        Map<String, BiConsumer<ChannelHandlerContext, FullHttpRequest>> map = new HashMap<>();
        map.put(API_LOGIN, this::handleLogin);
        map.put(API_REGISTER, this::handleRegister);
        map.put(API_USERS, this::handleGetAllUsers);
        map.put(API_ONLINE, this::handleOnline);
        map.put(API_UPLOAD, this::handleUpload);
        map.put(API_USER_PROFILE, this::handleUserProfile);
        map.put(API_ADMIN_USERS, this::handleAdminUsers);
        map.put(API_ADMIN_APPROVE, this::handleAdminApprove);
        map.put(API_ADMIN_REJECT, this::handleAdminReject);
        map.put(API_ADMIN_ACTION, this::handleAdminAction);
        map.put(API_ADMIN_ALL_USERS, this::handleAdminAllUsers);
        map.put(API_ADMIN_BROADCAST, this::handleAdminBroadcast);
        map.put(API_ADMIN_MESSAGE_RECALL, this::handleAdminMessageRecall);
        return map;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        String uri = req.uri();
        if (uri == null) uri = "";
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;

        if (path.startsWith("/api") || path.startsWith(FILES_PREFIX)) {
            dispatch(ctx, req, path);
            return;
        }
        ctx.fireChannelRead(req.retain());
    }

    private void dispatch(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        BiConsumer<ChannelHandlerContext, FullHttpRequest> handler = routes.get(path);
        if (handler != null) {
            handler.accept(ctx, req);
            return;
        }
        if (path.startsWith(FILES_PREFIX)) {
            serveFile(ctx, req, path);
            return;
        }
        req.release();
        sendJson(ctx, HttpResponseStatus.NOT_FOUND, Map.of("error", "Not found"));
    }

    // ==================== Auth ====================

    private void handleLogin(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        req.release();
        @SuppressWarnings("unchecked")
        Map<String, String> map = GSON.fromJson(body, Map.class);
        if (map == null) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "Invalid JSON"));
            return;
        }
        String username = map.get("username");
        if (username == null) username = map.get("userId");
        String password = map.get("password");
        String token = authService.login(username, password);
        if (token == null) {
            sendJson(ctx, HttpResponseStatus.UNAUTHORIZED, Map.of("error", "Invalid credentials or not approved"));
            return;
        }
        sendJson(ctx, HttpResponseStatus.OK, Map.of("token", token));
    }

    private void handleRegister(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        req.release();
        @SuppressWarnings("unchecked")
        Map<String, String> map = GSON.fromJson(body, Map.class);
        if (map == null) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "Invalid JSON"));
            return;
        }
        String username = map.get("username");
        String password = map.get("password");
        try {
            String err = authService.register(username, password);
            if (err != null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", err));
                return;
            }
            sendJson(ctx, HttpResponseStatus.OK, Map.of("ok", true, "message", "Registration submitted, awaiting admin approval"));
        } catch (IOException e) {
            log.warn("Register failed: {}", e.getMessage());
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    // ==================== Users ====================

    private void handleGetAllUsers(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.GET) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        req.release();
        if (token == null || jwtUtil.parseUserId(token) == null) {
            sendJson(ctx, HttpResponseStatus.UNAUTHORIZED, Map.of("error", "Login required"));
            return;
        }
        try {
            List<User> users = authService.getAllApprovedUsers();
            List<Map<String, Object>> res = new ArrayList<>();
            for (User u : users) {
                res.add(Map.of("userId", u.getUserId(), "username", u.getUsername() != null ? u.getUsername() : ""));
            }
            sendJson(ctx, HttpResponseStatus.OK, Map.of("users", res));
        } catch (IOException e) {
            log.warn("getAllApprovedUsers failed: {}", e.getMessage());
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private void handleOnline(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.GET) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        req.release();
        if (token == null || jwtUtil.parseUserId(token) == null) {
            sendJson(ctx, HttpResponseStatus.UNAUTHORIZED, Map.of("error", "Login required"));
            return;
        }
        List<String> users = registry.getOnlineUserIds();
        sendJson(ctx, HttpResponseStatus.OK, Map.of("users", users));
    }

    // ==================== User Profile ====================

    private void handleUserProfile(ChannelHandlerContext ctx, FullHttpRequest req) {
        String token = bearerToken(req);
        String username = token != null ? jwtUtil.parseUserId(token) : null;
        if (username == null) {
            req.release();
            sendJson(ctx, HttpResponseStatus.UNAUTHORIZED, Map.of("error", "Login required"));
            return;
        }

        if (req.method() == HttpMethod.GET) {
            req.release();
            try {
                User u = authService.getUserByUsernameOrId(username);
                if (u == null) {
                    sendJson(ctx, HttpResponseStatus.NOT_FOUND, Map.of("error", "User not found"));
                    return;
                }
                sendJson(ctx, HttpResponseStatus.OK, Map.of(
                        "nickname", u.getNickname() != null ? u.getNickname() : "",
                        "avatarUrl", u.getAvatarUrl() != null ? u.getAvatarUrl() : ""
                ));
            } catch (IOException e) {
                log.warn("Get profile failed: {}", e.getMessage());
                sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
            }
            return;
        }

        if (req.method() == HttpMethod.POST) {
            String body = req.content().toString(CharsetUtil.UTF_8);
            req.release();
            @SuppressWarnings("unchecked")
            Map<String, String> map = GSON.fromJson(body, Map.class);
            if (map == null) {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "Invalid JSON"));
                return;
            }
            String nickname = map.get("nickname");
            String avatarUrl = map.get("avatarUrl");
            try {
                authService.updateProfile(username, nickname, avatarUrl);
                sendJson(ctx, HttpResponseStatus.OK, Map.of("ok", true));
            } catch (IOException e) {
                log.warn("Update profile failed: {}", e.getMessage());
                sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
            }
            return;
        }

        req.release();
        sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
    }

    // ==================== File Upload ====================

    private void handleUpload(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        if (token == null || jwtUtil.parseUserId(token) == null) {
            req.release();
            sendJson(ctx, HttpResponseStatus.UNAUTHORIZED, Map.of("error", "Login required"));
            return;
        }
        HttpPostRequestDecoder decoder = null;
        try {
            decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), req);
            decoder.offer(new io.netty.handler.codec.http.DefaultLastHttpContent(req.content().retain()));
            String savedName = null;
            for (InterfaceHttpData data : decoder.getBodyHttpDatas()) {
                if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
                    FileUpload fu = (FileUpload) data;
                    if (fu.isCompleted()) {
                        savedName = fileStorage.store(fu.getFile(), fu.getFilename());
                        break;
                    }
                }
            }
            req.release();
            decoder.destroy();
            if (savedName != null) {
                sendJson(ctx, HttpResponseStatus.OK, Map.of("url", FILES_PREFIX + savedName));
            } else {
                sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "No file in upload"));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Upload failed: {}", e.getMessage());
            if (decoder != null) try { decoder.destroy(); } catch (Exception ignored) {}
            req.release();
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Upload failed"));
        }
    }

    private void serveFile(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        if (req.method() != HttpMethod.GET) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String filename = path.substring(FILES_PREFIX.length());
        req.release();
        try {
            byte[] content = fileStorage.read(filename);
            String contentType = fileStorage.contentType(filename);
            ByteBuf buf = Unpooled.copiedBuffer(content);
            DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
            resp.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                    .set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        } catch (FileStorageService.FileNotFoundException e) {
            sendJson(ctx, HttpResponseStatus.NOT_FOUND, Map.of("error", "Not found"));
        } catch (IOException e) {
            log.warn("Serve file failed: {}", e.getMessage());
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    // ==================== Admin ====================

    private void handleAdminUsers(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.GET) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        req.release();
        if (!isAdmin(token)) {
            sendJson(ctx, HttpResponseStatus.FORBIDDEN, Map.of("error", "Admin required"));
            return;
        }
        try {
            List<Long> pendingIds = authService.getPendingUserIds();
            List<Map<String, Object>> users = new ArrayList<>();
            for (Long id : pendingIds) {
                User u = authService.getUserByUserId(id);
                if (u != null) {
                    users.add(Map.<String, Object>of(
                            "userId", u.getUserId(),
                            "username", u.getUsername() != null ? u.getUsername() : ""
                    ));
                }
            }
            sendJson(ctx, HttpResponseStatus.OK, Map.of("users", users));
        } catch (IOException e) {
            log.warn("Admin users failed: {}", e.getMessage());
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private void handleAdminApprove(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        if (!isAdmin(token)) {
            req.release();
            sendJson(ctx, HttpResponseStatus.FORBIDDEN, Map.of("error", "Admin required"));
            return;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        req.release();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = GSON.fromJson(body, Map.class);
        if (map == null || !map.containsKey("userId")) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "userId required"));
            return;
        }
        long userId = ((Number) map.get("userId")).longValue();
        try {
            authService.approve(userId);
            sendJson(ctx, HttpResponseStatus.OK, Map.of("ok", true));
        } catch (IOException e) {
            log.warn("Approve failed: {}", e.getMessage());
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private void handleAdminReject(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        if (!isAdmin(token)) {
            req.release();
            sendJson(ctx, HttpResponseStatus.FORBIDDEN, Map.of("error", "Admin required"));
            return;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        req.release();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = GSON.fromJson(body, Map.class);
        if (map == null || !map.containsKey("userId")) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "userId required"));
            return;
        }
        long userId = ((Number) map.get("userId")).longValue();
        try {
            authService.reject(userId);
            sendJson(ctx, HttpResponseStatus.OK, Map.of("ok", true));
        } catch (IOException e) {
            log.warn("Reject failed: {}", e.getMessage());
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private void handleAdminAction(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        if (!isAdmin(token)) {
            req.release();
            sendJson(ctx, HttpResponseStatus.FORBIDDEN, Map.of("error", "Admin required"));
            return;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        req.release();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = GSON.fromJson(body, Map.class);
        if (map == null || !map.containsKey("userId") || !map.containsKey("action")) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "userId and action required"));
            return;
        }
        long targetUserId = ((Number) map.get("userId")).longValue();
        String action = (String) map.get("action");
        Number durationNum = (Number) map.get("durationHours");
        long durationMs = durationNum != null ? (long) (durationNum.doubleValue() * 3600 * 1000) : 0;
        long until = durationMs > 0 ? System.currentTimeMillis() + durationMs : 0;

        try {
            switch (action) {
                case "MUTE" -> authService.changeUserStatusWithDuration(targetUserId, "MUTED", until);
                case "UNMUTE" -> authService.changeUserStatusWithDuration(targetUserId, "APPROVED", 0);
                case "BAN" -> {
                    authService.changeUserStatusWithDuration(targetUserId, "BANNED", until);
                    User u = authService.getUserByUserId(targetUserId);
                    if (u != null && u.getUsername() != null) {
                        Channel ch = registry.get(u.getUsername());
                        if (ch != null && ch.isActive()) {
                            String notify = GSON.toJson(Map.of("type", "error", "reason", "BANNED_NOTIFY"));
                            ch.writeAndFlush(new TextWebSocketFrame(notify))
                              .addListener(ChannelFutureListener.CLOSE);
                        }
                    }
                }
                case "UNBAN" -> authService.changeUserStatusWithDuration(targetUserId, "APPROVED", 0);
                default -> {
                    sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "Invalid action"));
                    return;
                }
            }
            sendJson(ctx, HttpResponseStatus.OK, Map.of("ok", true));
        } catch (IOException e) {
            log.warn("Admin action failed: {}", e.getMessage());
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private void handleAdminAllUsers(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.GET) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        req.release();
        if (!isAdmin(token)) {
            sendJson(ctx, HttpResponseStatus.FORBIDDEN, Map.of("error", "Admin required"));
            return;
        }
        try {
            List<User> users = authService.getAllUsers();
            List<Map<String, Object>> res = new ArrayList<>();
            for (User u : users) {
                res.add(Map.<String, Object>of(
                        "userId", u.getUserId(),
                        "username", u.getUsername() != null ? u.getUsername() : "",
                        "status", u.getStatus() != null ? u.getStatus() : ""
                ));
            }
            sendJson(ctx, HttpResponseStatus.OK, Map.of("users", res));
        } catch (IOException e) {
            log.warn("getAllUsers failed: {}", e.getMessage());
            sendJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    // ==================== Admin Broadcast & Recall ====================

    private void handleAdminBroadcast(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        if (!isAdmin(token)) {
            req.release();
            sendJson(ctx, HttpResponseStatus.FORBIDDEN, Map.of("error", "Admin required"));
            return;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        req.release();
        @SuppressWarnings("unchecked")
        Map<String, String> map = GSON.fromJson(body, Map.class);
        if (map == null || map.get("message") == null || map.get("message").isBlank()) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "message required"));
            return;
        }
        String message = map.get("message").trim();
        Map<String, Object> payload = Map.of(
                "type", TYPE_SYSTEM,
                "senderId", SENDER_SYSTEM,
                "content", message,
                "timestamp", System.currentTimeMillis()
        );
        String json = GSON.toJson(payload);
        TextWebSocketFrame frame = new TextWebSocketFrame(json);
        int sent = 0;
        for (Channel ch : registry.getAllChannels()) {
            if (ch.isActive()) {
                ch.writeAndFlush(frame.retainedDuplicate());
                sent++;
            }
        }
        frame.release();
        log.info("Admin broadcast sent to {} clients: {}", sent, message);
        sendJson(ctx, HttpResponseStatus.OK, Map.of("ok", true, "sent", sent));
    }

    private void handleAdminMessageRecall(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            req.release();
            sendJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        if (!isAdmin(token)) {
            req.release();
            sendJson(ctx, HttpResponseStatus.FORBIDDEN, Map.of("error", "Admin required"));
            return;
        }
        String body = req.content().toString(CharsetUtil.UTF_8);
        req.release();
        @SuppressWarnings("unchecked")
        Map<String, Object> map = GSON.fromJson(body, Map.class);
        if (map == null || map.get("messageId") == null) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "messageId required"));
            return;
        }
        String messageIdStr = String.valueOf(map.get("messageId")).trim();
        long messageId;
        try {
            messageId = Long.parseLong(messageIdStr);
        } catch (NumberFormatException e) {
            sendJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "Invalid messageId"));
            return;
        }
        Map<String, Object> payload = Map.of(
                "type", TYPE_RECALL,
                "messageId", messageId,
                "senderId", SENDER_SYSTEM,
                "timestamp", System.currentTimeMillis()
        );
        String json = GSON.toJson(payload);
        TextWebSocketFrame frame = new TextWebSocketFrame(json);
        int sent = 0;
        for (Channel ch : registry.getAllChannels()) {
            if (ch.isActive()) {
                ch.writeAndFlush(frame.retainedDuplicate());
                sent++;
            }
        }
        frame.release();
        log.info("Admin force recall messageId={} to {} clients", messageId, sent);
        sendJson(ctx, HttpResponseStatus.OK, Map.of("ok", true, "sent", sent));
    }

    // ==================== Utils ====================

    private static String bearerToken(FullHttpRequest req) {
        CharSequence auth = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth == null) return null;
        String s = auth.toString();
        if (s.startsWith("Bearer ")) return s.substring(7).trim();
        return null;
    }

    private boolean isAdmin(String token) {
        return token != null && "ADMIN".equals(jwtUtil.parseRole(token));
    }

    private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object body) {
        String json = GSON.toJson(body);
        ByteBuf buf = Unpooled.copiedBuffer(json, CharsetUtil.UTF_8);
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
                .set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }
}
