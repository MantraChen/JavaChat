package com.chat.netty;

import com.chat.auth.AuthService;
import com.chat.auth.JwtUtil;
import com.chat.model.Message;
import com.chat.neurodb.NeuroDbClient;
import com.chat.protocol.AckPacket;
import com.chat.protocol.AuthFailPacket;
import com.chat.protocol.AuthOkPacket;
import com.chat.protocol.ChatMessagePacket;
import com.chat.protocol.ErrorPacket;
import com.chat.protocol.RecallPacket;
import com.chat.protocol.SyncResultPacket;
import com.chat.redis.RedisChatBus;
import com.chat.util.TimelineKeyUtil;
import com.google.gson.Gson;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
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

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
    private final RedisChatBus redisBus;
    private final String uploadDir;

    /** 当前连接对应的 userId，未认证为 null */
    private String currentUserId;

    public ChatWebSocketHandler(AuthService authService, JwtUtil jwtUtil, NeuroDbClient neuroDb,
                               ChannelRegistry registry, RedisChatBus redisBus, String uploadDir) {
        this.authService = authService;
        this.jwtUtil = jwtUtil;
        this.neuroDb = neuroDb;
        this.registry = registry;
        this.redisBus = redisBus != null ? redisBus : new RedisChatBus("", 6379, registry);
        this.uploadDir = uploadDir != null ? uploadDir : "upload";
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
                case "typing":
                    handleTyping(ctx, map);
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
        Message msg = new Message(0L, currentUserId, content, ts);
        Object rtId = map.get("replyToId");
        if (rtId != null) {
            long replyToIdLong;
            if (rtId instanceof String) {
                try { replyToIdLong = Long.parseLong((String) rtId); } catch (NumberFormatException e) { replyToIdLong = 0L; }
            } else if (rtId instanceof Number) {
                replyToIdLong = ((Number) rtId).longValue();
            } else { replyToIdLong = 0L; }
            if (replyToIdLong != 0L) msg.setReplyToId(replyToIdLong);
        }
        String rtUser = (String) map.get("replyToUser");
        if (rtUser != null) msg.setReplyToUser(rtUser);
        String rtContent = (String) map.get("replyToContent");
        if (rtContent != null) msg.setReplyToContent(rtContent);
        @SuppressWarnings("unchecked")
        List<String> mentions = (List<String>) map.get("mentions");
        if (mentions != null) msg.setMentions(mentions);
        String receiverId = (String) map.get("receiverId");
        if (receiverId != null) receiverId = receiverId.trim();
        msg.setReceiverId(receiverId);
        String msgType = (String) map.get("msgType");
        if (msgType != null && !msgType.isEmpty()) msg.setMsgType(msgType);

        int senderOwnerId = TimelineKeyUtil.userIdToOwnerId(currentUserId);
        int receiverOwnerId = TimelineKeyUtil.userIdToOwnerId(receiverId);
        boolean isPublic = (receiverId == null || receiverId.isEmpty() || "PUBLIC".equalsIgnoreCase(receiverId));
        String value;
        try {
            if (isPublic) {
                long publicKey = TimelineKeyUtil.buildKey(0, ts);
                msg.setMessageId(publicKey);
                value = GSON.toJson(msg);
                neuroDb.put(publicKey, value);
            } else {
                long senderKey = TimelineKeyUtil.buildKey(senderOwnerId, ts);
                long receiverKey = TimelineKeyUtil.buildKey(receiverOwnerId, ts);
                msg.setMessageId(senderKey);
                value = GSON.toJson(msg);
                neuroDb.put(senderKey, value);
                if (senderOwnerId != receiverOwnerId) {
                    msg.setMessageId(receiverKey);
                    neuroDb.put(receiverKey, GSON.toJson(msg));
                }
            }
        } catch (IOException e) {
            log.error("NeuroDB put failed: {}", e.getMessage());
            sendError(ctx, "Storage failed");
            return;
        }

        ChatMessagePacket packet = new ChatMessagePacket();
        packet.messageId = String.valueOf(msg.getMessageId());
        packet.senderId = currentUserId;
        packet.content = content;
        packet.timestamp = ts;
        packet.replyToId = msg.getReplyToId() == null ? null : String.valueOf(msg.getReplyToId());
        packet.replyToUser = msg.getReplyToUser();
        packet.replyToContent = msg.getReplyToContent();
        packet.mentions = msg.getMentions();
        packet.receiverId = receiverId;
        packet.msgType = msg.getMsgType();
        String json = GSON.toJson(packet);
        String localId = (String) map.get("localId");
        if (redisBus.isEnabled()) {
            redisBus.publish(json);
            // 【关键】立刻给自己回写一条，否则 Redis 订阅者里 broadcast 会跳过发送者，自己永远收不到
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        } else {
            if (receiverId == null || receiverId.isEmpty() || "PUBLIC".equalsIgnoreCase(receiverId)) {
                registry.broadcast(currentUserId, json);
                ctx.writeAndFlush(new TextWebSocketFrame(json));
            } else {
                Channel targetCh = registry.get(receiverId);
                if (targetCh != null && targetCh.isActive()) {
                    targetCh.writeAndFlush(new TextWebSocketFrame(json));
                }
                ctx.writeAndFlush(new TextWebSocketFrame(json));
            }
        }
        if (localId != null && !localId.isEmpty()) {
            AckPacket ack = new AckPacket();
            ack.localId = localId;
            ack.messageId = String.valueOf(msg.getMessageId());
            ctx.writeAndFlush(new TextWebSocketFrame(GSON.toJson(ack)));
        }
    }

    private static final long RECALL_MAX_MS = 2 * 60 * 1000L;

    private void handleRecall(ChannelHandlerContext ctx, Map<String, Object> map) {
        Object midObj = map.get("messageId");
        if (midObj == null) { sendError(ctx, "messageId required"); return; }
        long messageId;
        if (midObj instanceof String) {
            try { messageId = Long.parseLong((String) midObj); } catch (NumberFormatException e) { sendError(ctx, "Invalid messageId"); return; }
        } else if (midObj instanceof Number) {
            messageId = ((Number) midObj).longValue();
        } else { sendError(ctx, "messageId required"); return; }
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
        String recalledJson = GSON.toJson(msg);
        try {
            neuroDb.put(messageId, recalledJson);
            String recvId = msg.getReceiverId();
            if (recvId != null && !recvId.isEmpty() && !"PUBLIC".equalsIgnoreCase(recvId)) {
                int senderOwnerId = TimelineKeyUtil.userIdToOwnerId(msg.getSenderId());
                int receiverOwnerId = TimelineKeyUtil.userIdToOwnerId(recvId);
                long ts = msg.getTimestamp();
                long senderKey = TimelineKeyUtil.buildKey(senderOwnerId, ts);
                long receiverKey = TimelineKeyUtil.buildKey(receiverOwnerId, ts);
                if (messageId != senderKey) neuroDb.put(senderKey, recalledJson);
                if (messageId != receiverKey) neuroDb.put(receiverKey, recalledJson);
            }
        } catch (IOException e) {
            sendError(ctx, "Recall failed"); return;
        }
        RecallPacket recall = new RecallPacket();
        recall.messageId = String.valueOf(messageId);
        recall.senderId = currentUserId;
        String recallJson = GSON.toJson(recall);
        if (redisBus.isEnabled()) {
            redisBus.publish(recallJson);
            ctx.writeAndFlush(new TextWebSocketFrame(recallJson));
        } else {
            registry.broadcast(null, recallJson);
            ctx.writeAndFlush(new TextWebSocketFrame(recallJson));
        }
    }

    /** 正在输入：不落库，直接推 Redis 或本机投递。 */
    private void handleTyping(ChannelHandlerContext ctx, Map<String, Object> map) {
        Object statusObj = map.get("status");
        boolean status = Boolean.TRUE.equals(statusObj);
        String receiverId = (String) map.get("receiverId");
        if (receiverId != null) receiverId = receiverId.trim();
        Map<String, Object> typing = Map.of(
                "type", "typing",
                "senderId", currentUserId,
                "receiverId", receiverId == null ? "" : receiverId,
                "status", status
        );
        String json = GSON.toJson(typing);
        if (redisBus.isEnabled()) {
            redisBus.publish(json);
        } else {
            if (receiverId == null || receiverId.isEmpty() || "PUBLIC".equalsIgnoreCase(receiverId)) {
                registry.broadcast(currentUserId, json);
            } else {
                Channel targetCh = registry.get(receiverId);
                if (targetCh != null && targetCh.isActive()) {
                    targetCh.writeAndFlush(new TextWebSocketFrame(json));
                }
            }
        }
    }

    /** 按信箱前缀 O(log N) 精确扫描。target 为空或 PUBLIC 拉大厅(owner=0)，否则拉当前用户私信箱(写扩散已投递)。 */
    private void handleSync(ChannelHandlerContext ctx, Map<String, Object> map) {
        String target = (String) map.get("target");
        boolean isPublicSync = (target == null || target.isEmpty() || "PUBLIC".equalsIgnoreCase(target));
        int targetOwnerId = isPublicSync ? 0 : TimelineKeyUtil.userIdToOwnerId(currentUserId);

        Object tsObj = map.get("lastTimestamp");
        long lastTs = tsObj instanceof Number ? ((Number) tsObj).longValue() : 0L;
        long startKey = TimelineKeyUtil.buildKey(targetOwnerId, lastTs <= 0 ? 0 : lastTs + 1);
        long endKey = TimelineKeyUtil.buildKey(targetOwnerId, Long.MAX_VALUE);

        List<ChatMessagePacket> list = new ArrayList<>();
        try {
            for (NeuroDbClient.ScanRecord rec : neuroDb.scan(startKey, endKey)) {
                if (rec.value == null || rec.value.isBlank() || !rec.value.trim().startsWith("{")) continue;
                try {
                    Message m = GSON.fromJson(rec.value, Message.class);
                    if (m == null || m.getSenderId() == null || m.getSenderId().isEmpty()) continue;
                    ChatMessagePacket p = new ChatMessagePacket();
                    p.messageId = String.valueOf(rec.key);
                    p.senderId = m.getSenderId();
                    p.content = m.isRecalled() ? "" : (m.getContent() != null ? m.getContent() : "");
                    p.timestamp = m.getTimestamp();
                    p.isRecalled = m.isRecalled();
                    p.replyToId = m.getReplyToId() == null ? null : String.valueOf(m.getReplyToId());
                    p.replyToUser = m.getReplyToUser();
                    p.replyToContent = m.getReplyToContent();
                    p.mentions = m.getMentions();
                    p.receiverId = m.getReceiverId();
                    p.msgType = m.getMsgType();
                    list.add(p);
                } catch (Exception e) {
                    // 忽略非 Message 结构
                }
            }
        } catch (IOException e) {
            log.warn("NeuroDB scan failed: {}", e.getMessage());
            sendError(ctx, "Sync failed");
            return;
        }
        list.sort(Comparator.comparingLong(p -> p.timestamp));
        SyncResultPacket result = new SyncResultPacket();
        result.target = isPublicSync ? "PUBLIC" : "INBOX";
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
        if ("/api/upload".equals(path)) {
            handleUpload(ctx, req);
            return;
        }
        if (path.startsWith("/files/")) {
            serveFile(ctx, req, path);
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

    private void handleUpload(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (req.method() != io.netty.handler.codec.http.HttpMethod.POST) {
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
            decoder.offer(new DefaultLastHttpContent(req.content().retain()));
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
                String fileUrl = "/files/" + savedName;
                sendHttpJson(ctx, HttpResponseStatus.OK, Map.of("url", fileUrl));
            } else {
                sendHttpJson(ctx, HttpResponseStatus.BAD_REQUEST, Map.of("error", "No file in upload"));
            }
        } catch (Exception e) {
            log.warn("Upload failed: {}", e.getMessage());
            if (decoder != null) try { decoder.destroy(); } catch (Exception ignored) {}
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Map.of("error", "Upload failed"));
        }
    }

    private void serveFile(ChannelHandlerContext ctx, FullHttpRequest req, String path) {
        if (req.method() != io.netty.handler.codec.http.HttpMethod.GET) {
            req.release();
            sendHttpJson(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, Map.of("error", "Method not allowed"));
            return;
        }
        String name = path.substring("/files/".length()).replaceAll("[^a-zA-Z0-9._-]", "");
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
            io.netty.handler.codec.http.FullHttpResponse resp = new io.netty.handler.codec.http.DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buf);
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
