package com.chat.network.http;

import com.chat.auth.AuthService;
import com.chat.auth.JwtUtil;
import com.chat.model.User;
import com.chat.network.netty.ChannelRegistry;

import static com.chat.core.ProtocolConsts.*;
import com.google.gson.Gson;
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
import io.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 专门处理 HTTP API 与静态文件下载：/api/* 与 /files/*。
 * 与 WebSocket 完全分离，不处理 /ws 升级请求（交给下游 WebSocketServerProtocolHandler）。
 */
public class HttpDispatcherHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(HttpDispatcherHandler.class);
    private static final Gson GSON = new Gson();

    private final AuthService authService;
    private final JwtUtil jwtUtil;
    private final ChannelRegistry registry;
    private final String uploadDir;

    public HttpDispatcherHandler(AuthService authService, JwtUtil jwtUtil,
                                  ChannelRegistry registry, String uploadDir) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.registry = registry;
        this.uploadDir = uploadDir != null ? uploadDir : "upload";
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
        if (API_LOGIN.equals(path)) {
            handleLogin(ctx, req);
            return;
        }
        if (API_REGISTER.equals(path)) {
            handleRegister(ctx, req);
            return;
        }
        if (API_ADMIN_USERS.equals(path)) {
            handleAdminUsers(ctx, req);
            return;
        }
        if (API_ADMIN_APPROVE.equals(path)) {
            handleAdminApprove(ctx, req);
            return;
        }
        if (API_ADMIN_REJECT.equals(path)) {
            handleAdminReject(ctx, req);
            return;
        }
        if (API_ONLINE.equals(path)) {
            handleOnline(ctx, req);
            return;
        }
        if (API_USERS.equals(path)) {
            handleGetAllUsers(ctx, req);
            return;
        }
        if (API_ADMIN_ACTION.equals(path)) {
            handleAdminAction(ctx, req);
            return;
        }
        if (API_ADMIN_ALL_USERS.equals(path)) {
            handleAdminAllUsers(ctx, req);
            return;
        }
        if (API_UPLOAD.equals(path)) {
            handleUpload(ctx, req);
            return;
        }
        if (path.startsWith(FILES_PREFIX)) {
            serveFile(ctx, req, path);
            return;
        }
        req.release();
        sendHttpJson(ctx, HttpResponseStatus.NOT_FOUND, Map.of("error", "Not found"));
    }

    private void handleGetAllUsers(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.GET) {
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
        try {
            List<User> users = authService.getAllApprovedUsers();
            List<Map<String, Object>> res = new ArrayList<>();
            for (User u : users) {
                res.add(Map.of("userId", u.getUserId(), "username", u.getUsername() != null ? u.getUsername() : ""));
            }
            sendHttpJson(ctx, HttpResponseStatus.OK, Map.of("users", res));
        } catch (IOException e) {
            log.warn("getAllApprovedUsers failed: {}", e.getMessage());
            sendHttpJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private void handleAdminAction(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.POST) {
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
        if (map == null || !map.containsKey("userId") || !map.containsKey("action")) {
            sendHttpJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "userId and action required"));
            return;
        }
        int targetUserId = ((Number) map.get("userId")).intValue();
        String action = (String) map.get("action");
        try {
            switch (action) {
                case "MUTE" -> authService.changeUserStatus(targetUserId, "MUTED");
                case "UNMUTE" -> authService.changeUserStatus(targetUserId, "APPROVED");
                case "BAN" -> {
                    authService.changeUserStatus(targetUserId, "BANNED");
                    User u = authService.getUserByUserId(targetUserId);
                    if (u != null) {
                        String username = u.getUsername();
                        if (username != null) {
                            Channel ch = registry.get(username);
                            if (ch != null && ch.isActive()) ch.close();
                        }
                    }
                }
                case "UNBAN" -> authService.changeUserStatus(targetUserId, "APPROVED");
                default -> {
                    sendHttpJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "Invalid action"));
                    return;
                }
            }
            sendHttpJson(ctx, HttpResponseStatus.OK, Map.of("ok", true));
        } catch (IOException e) {
            log.warn("Admin action failed: {}", e.getMessage());
            sendHttpJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private void handleAdminAllUsers(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.GET) {
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
            List<User> users = authService.getAllUsers();
            List<Map<String, Object>> res = new ArrayList<>();
            for (User u : users) {
                res.add(Map.<String, Object>of(
                        "userId", u.getUserId(),
                        "username", u.getUsername() != null ? u.getUsername() : "",
                        "status", u.getStatus() != null ? u.getStatus() : ""
                ));
            }
            sendHttpJson(ctx, HttpResponseStatus.OK, Map.of("users", res));
        } catch (IOException e) {
            log.warn("getAllUsers failed: {}", e.getMessage());
            sendHttpJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private void handleOnline(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.GET) {
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
        if (req.method() != HttpMethod.POST) {
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
        if (req.method() != HttpMethod.POST) {
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

    private static String bearerToken(FullHttpRequest req) {
        CharSequence auth = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth == null) return null;
        String s = auth.toString();
        if (s.startsWith("Bearer ")) return s.substring(7).trim();
        return null;
    }

    private void handleAdminUsers(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.GET) {
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
                User u = authService.getUserByUserId(id.intValue());
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
        if (req.method() != HttpMethod.POST) {
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
        if (req.method() != HttpMethod.POST) {
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

    private void handleUpload(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String token = bearerToken(req);
        if (token == null || jwtUtil.parseUserId(token) == null) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.UNAUTHORIZED, Map.of("error", "Login required"));
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
                        String ext = "";
                        String fn = fu.getFilename();
                        if (fn != null && fn.contains(".")) ext = fn.substring(fn.lastIndexOf('.'));
                        if (!ext.matches("(?i)\\.(jpe?g|png|gif|webp)")) ext = ".jpg";
                        Path dir = Paths.get(uploadDir);
                        Files.createDirectories(dir);
                        savedName = UUID.randomUUID().toString() + ext;
                        Path dest = dir.resolve(savedName);
                        Files.copy(fu.getFile().toPath(), dest);
                        break;
                    }
                }
            }
            req.release();
            decoder.destroy();
            if (savedName != null) {
                String fileUrl = FILES_PREFIX + savedName;
                sendHttpJson(ctx, HttpResponseStatus.OK, Map.of("url", fileUrl));
            } else {
                sendHttpJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "No file in upload"));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Upload failed: {}", e.getMessage());
            if (decoder != null) try { decoder.destroy(); } catch (Exception ignored) {}
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Upload failed"));
        }
    }

    private void serveFile(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        if (req.method() != HttpMethod.GET) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String name = path.substring(FILES_PREFIX.length()).replaceAll("[^a-zA-Z0-9._-]", "");
        if (name.isEmpty()) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "Invalid path"));
            return;
        }
        try {
            Path base = Paths.get(uploadDir).toAbsolutePath().normalize();
            Path file = base.resolve(name).normalize();
            if (!file.startsWith(base)) {
                req.release();
                sendHttpJson(ctx, HttpResponseStatus.FORBIDDEN, Map.of("error", "Forbidden"));
                return;
            }
            if (!Files.isRegularFile(file)) {
                req.release();
                sendHttpJson(ctx, HttpResponseStatus.NOT_FOUND, Map.of("error", "Not found"));
                return;
            }
            byte[] body = Files.readAllBytes(file);
            req.release();
            String contentType = "application/octet-stream";
            String lower = name.toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) contentType = "image/jpeg";
            else if (lower.endsWith(".png")) contentType = "image/png";
            else if (lower.endsWith(".gif")) contentType = "image/gif";
            else if (lower.endsWith(".webp")) contentType = "image/webp";
            io.netty.buffer.ByteBuf buf = Unpooled.copiedBuffer(body);
            DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
            resp.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE, contentType)
                    .set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
        } catch (IOException e) {
            log.warn("Serve file failed: {}", e.getMessage());
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Server error"));
        }
    }

    private void sendHttpJson(ChannelHandlerContext ctx, HttpResponseStatus status, Object body) {
        String json = GSON.toJson(body);
        io.netty.buffer.ByteBuf buf = Unpooled.copiedBuffer(json, CharsetUtil.UTF_8);
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, buf);
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
                .set(HttpHeaderNames.CONTENT_LENGTH, buf.readableBytes());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }
}
