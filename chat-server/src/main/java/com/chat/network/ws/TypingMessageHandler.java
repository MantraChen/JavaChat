package com.chat.network.ws;

import com.chat.core.ProtocolConsts;
import com.chat.network.netty.ChannelRegistry;
import com.chat.redis.RedisChatBus;
import com.google.gson.Gson;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.Map;

/**
 * 处理 type=typing：不落库，直接推 Redis 或本机广播。
 */
public class TypingMessageHandler implements MessageHandler {
    private static final Gson GSON = new Gson();

    private final ChannelRegistry registry;
    private final RedisChatBus redisBus;

    public TypingMessageHandler(ChannelRegistry registry, RedisChatBus redisBus) {
        this.registry = registry;
        this.redisBus = redisBus;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Map<String, Object> payload, String userId) {
        Object statusObj = payload != null ? payload.get("status") : null;
        boolean status = Boolean.TRUE.equals(statusObj);
        String receiverId = payload != null ? (String) payload.get("receiverId") : null;
        if (receiverId != null) receiverId = receiverId.trim();

        Map<String, Object> typing = Map.of(
                "type", ProtocolConsts.TYPE_TYPING,
                "senderId", userId,
                "receiverId", receiverId == null ? "" : receiverId,
                "status", status
        );
        String json = GSON.toJson(typing);
        if (redisBus != null && redisBus.isEnabled()) {
            redisBus.publish(json);
        } else {
            if (receiverId == null || receiverId.isEmpty() || ProtocolConsts.TARGET_PUBLIC.equalsIgnoreCase(receiverId)) {
                registry.broadcast(userId, json);
            } else {
                Channel targetCh = registry.get(receiverId);
                if (targetCh != null && targetCh.isActive()) {
                    targetCh.writeAndFlush(new TextWebSocketFrame(json));
                }
            }
        }
    }
}
