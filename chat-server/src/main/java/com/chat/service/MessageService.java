package com.chat.service;

import com.chat.core.ProtocolConsts;
import com.chat.model.Message;
import com.chat.neurodb.NeuroDbClient;
import com.chat.protocol.ChatMessagePacket;
import com.chat.protocol.RecallPacket;
import com.chat.protocol.SyncResultPacket;
import com.chat.util.TimelineKeyUtil;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 消息业务层：落库、同步、撤回。与 Netty/Redis 解耦，便于单测。
 */
public class MessageService {
    private static final Gson GSON = new Gson();
    private static final long RECALL_MAX_MS = 2 * 60 * 1000L;

    private final NeuroDbClient neuroDb;

    public MessageService(NeuroDbClient neuroDb) {
        this.neuroDb = neuroDb;
    }

    /**
     * 发送消息：写 NeuroDB（公聊一条 key，私聊写扩散两条 key），返回要广播的 ChatMessagePacket 与 messageId（用于 ack）。
     */
    public SendResult sendMessage(String senderId, Map<String, Object> payload) throws IOException {
        String content = payload != null ? (String) payload.get("content") : null;
        if (content == null) content = "";
        long ts = System.currentTimeMillis();
        Message msg = new Message(0L, senderId, content, ts);

        Object rtId = payload != null ? payload.get("replyToId") : null;
        if (rtId != null) {
            long replyToIdLong = toLong(rtId);
            if (replyToIdLong != 0L) msg.setReplyToId(replyToIdLong);
        }
        if (payload != null) {
            String rtUser = (String) payload.get("replyToUser");
            if (rtUser != null) msg.setReplyToUser(rtUser);
            String rtContent = (String) payload.get("replyToContent");
            if (rtContent != null) msg.setReplyToContent(rtContent);
            @SuppressWarnings("unchecked")
            List<String> mentions = (List<String>) payload.get("mentions");
            if (mentions != null) msg.setMentions(mentions);
        }
        String receiverId = payload != null ? (String) payload.get("receiverId") : null;
        if (receiverId != null) receiverId = receiverId.trim();
        msg.setReceiverId(receiverId);
        String msgType = payload != null ? (String) payload.get("msgType") : null;
        if (msgType != null && !msgType.isEmpty()) msg.setMsgType(msgType);

        int senderOwnerId = TimelineKeyUtil.userIdToOwnerId(senderId);
        int receiverOwnerId = TimelineKeyUtil.userIdToOwnerId(receiverId);
        boolean isPublic = (receiverId == null || receiverId.isEmpty()
                || ProtocolConsts.TARGET_PUBLIC.equalsIgnoreCase(receiverId));
        String value = GSON.toJson(msg);

        if (isPublic) {
            long publicKey = TimelineKeyUtil.buildKey(0, ts);
            msg.setMessageId(publicKey);
            neuroDb.put(publicKey, value);
        } else {
            long senderKey = TimelineKeyUtil.buildKey(senderOwnerId, ts);
            long receiverKey = TimelineKeyUtil.buildKey(receiverOwnerId, ts);
            msg.setMessageId(senderKey);
            neuroDb.put(senderKey, value);
            if (senderOwnerId != receiverOwnerId) {
                msg.setMessageId(receiverKey);
                neuroDb.put(receiverKey, GSON.toJson(msg));
            }
        }

        ChatMessagePacket packet = toChatMessagePacket(msg, content, receiverId);
        return new SendResult(packet, msg.getMessageId());
    }

    /**
     * 按信箱同步：target 为空或 PUBLIC 拉大厅，否则拉当前用户 INBOX。
     */
    public SyncResult sync(String userId, String target, long lastTimestamp) throws IOException {
        boolean isPublicSync = (target == null || target.isEmpty()
                || ProtocolConsts.TARGET_PUBLIC.equalsIgnoreCase(target));
        int targetOwnerId = isPublicSync ? 0 : TimelineKeyUtil.userIdToOwnerId(userId);
        long startKey = TimelineKeyUtil.buildKey(targetOwnerId, lastTimestamp <= 0 ? 0 : lastTimestamp + 1);
        long endKey = TimelineKeyUtil.buildKey(targetOwnerId, Long.MAX_VALUE);

        List<ChatMessagePacket> list = new ArrayList<>();
        for (NeuroDbClient.ScanRecord rec : neuroDb.scan(startKey, endKey)) {
            if (rec.value == null || rec.value.isBlank() || !rec.value.trim().startsWith("{")) continue;
            try {
                Message m = GSON.fromJson(rec.value, Message.class);
                if (m == null || m.getSenderId() == null || m.getSenderId().isEmpty()) continue;
                ChatMessagePacket p = toChatMessagePacketFromMessage(rec.key, m);
                list.add(p);
            } catch (JsonSyntaxException ignored) {
                // 忽略非 Message 结构
            }
        }
        list.sort(Comparator.comparingLong(p -> p.timestamp));
        String resultTarget = isPublicSync ? ProtocolConsts.TARGET_PUBLIC : ProtocolConsts.TARGET_INBOX;
        SyncResultPacket result = new SyncResultPacket();
        result.target = resultTarget;
        result.messages = list;
        return new SyncResult(result);
    }

    /**
     * 撤回消息：仅允许 2 分钟内且本人消息。
     */
    public RecallPacket recall(String userId, long messageId) throws IOException {
        String json = neuroDb.get(messageId);
        if (json == null || json.isBlank()) throw new MessageServiceException("Message not found");
        Message msg = GSON.fromJson(json, Message.class);
        if (msg == null || !userId.equals(msg.getSenderId()))
            throw new MessageServiceException("Not your message");
        if (msg.isRecalled()) throw new MessageServiceException("Already recalled");
        long age = System.currentTimeMillis() - msg.getTimestamp();
        if (age > RECALL_MAX_MS) throw new MessageServiceException("Recall timeout (2 min)");

        msg.setContent("");
        msg.setRecalled(true);
        String recalledJson = GSON.toJson(msg);
        neuroDb.put(messageId, recalledJson);

        String recvId = msg.getReceiverId();
        if (recvId != null && !recvId.isEmpty() && !ProtocolConsts.TARGET_PUBLIC.equalsIgnoreCase(recvId)) {
            int senderOwnerId = TimelineKeyUtil.userIdToOwnerId(msg.getSenderId());
            int receiverOwnerId = TimelineKeyUtil.userIdToOwnerId(recvId);
            long ts = msg.getTimestamp();
            long senderKey = TimelineKeyUtil.buildKey(senderOwnerId, ts);
            long receiverKey = TimelineKeyUtil.buildKey(receiverOwnerId, ts);
            if (messageId != senderKey) neuroDb.put(senderKey, recalledJson);
            if (messageId != receiverKey) neuroDb.put(receiverKey, recalledJson);
        }

        RecallPacket recall = new RecallPacket();
        recall.messageId = String.valueOf(messageId);
        recall.senderId = userId;
        return recall;
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
        }
        return 0L;
    }

    private static ChatMessagePacket toChatMessagePacket(Message msg, String content, String receiverId) {
        ChatMessagePacket p = new ChatMessagePacket();
        p.messageId = String.valueOf(msg.getMessageId());
        p.senderId = msg.getSenderId();
        p.content = content;
        p.timestamp = msg.getTimestamp();
        p.replyToId = msg.getReplyToId() == null ? null : String.valueOf(msg.getReplyToId());
        p.replyToUser = msg.getReplyToUser();
        p.replyToContent = msg.getReplyToContent();
        p.mentions = msg.getMentions();
        p.receiverId = receiverId;
        p.msgType = msg.getMsgType();
        return p;
    }

    private static ChatMessagePacket toChatMessagePacketFromMessage(long key, Message m) {
        ChatMessagePacket p = new ChatMessagePacket();
        p.messageId = String.valueOf(key);
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
        return p;
    }

    public static final class SendResult {
        public final ChatMessagePacket packet;
        public final long messageId;

        public SendResult(ChatMessagePacket packet, long messageId) {
            this.packet = packet;
            this.messageId = messageId;
        }
    }

    public static final class SyncResult {
        public final SyncResultPacket packet;

        public SyncResult(SyncResultPacket packet) {
            this.packet = packet;
        }
    }

    public static final class MessageServiceException extends RuntimeException {
        public MessageServiceException(String message) {
            super(message);
        }
    }
}
