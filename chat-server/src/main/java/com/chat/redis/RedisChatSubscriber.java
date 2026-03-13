package com.chat.redis;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chat.protocol.ChatMessagePacket;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import redis.clients.jedis.JedisPubSub;

/**
 * Redis 订阅者：从 channel:chat_messages 收到集群内广播的聊天/撤回消息后，
 * 在本节点本地 ChannelRegistry 中投递（群聊广播、私信只发给本机连接的目标用户）。
 */
public class RedisChatSubscriber extends JedisPubSub {
    private static final Logger log = LoggerFactory.getLogger(RedisChatSubscriber.class);
    private static final Gson GSON = new Gson();

    private final com.chat.netty.ChannelRegistry localRegistry;

    public RedisChatSubscriber(com.chat.netty.ChannelRegistry localRegistry) {
        this.localRegistry = localRegistry;
    }

    @Override
    public void onMessage(String channel, String message) {
        try {
            log.trace("Redis onMessage channel={} len={}", channel, message != null ? message.length() : 0);
            @SuppressWarnings("unchecked")
            Map<String, Object> map = GSON.fromJson(message, Map.class);
            if (map == null) return;
            String type = (String) map.get("type");
            if (type == null) type = "";

            if ("RECALL".equals(type)) {
                // 撤回：向本节点所有连接广播
                localRegistry.broadcast(null, message);
                return;
            }

            if ("typing".equals(type)) {
                String receiverId = (String) map.get("receiverId");
                String senderId = (String) map.get("senderId");
                if (receiverId == null || receiverId.isEmpty() || "PUBLIC".equalsIgnoreCase(receiverId)) {
                    localRegistry.broadcast(senderId, message);
                } else {
                    Channel targetCh = localRegistry.get(receiverId);
                    if (targetCh != null && targetCh.isActive()) {
                        targetCh.writeAndFlush(new TextWebSocketFrame(message));
                    }
                }
                return;
            }

            if ("chat".equals(type)) {
                ChatMessagePacket packet = GSON.fromJson(message, ChatMessagePacket.class);
                if (packet == null) return;
                String receiverId = packet.receiverId;
                if (receiverId == null || receiverId.isEmpty() || "PUBLIC".equalsIgnoreCase(receiverId)) {
                    localRegistry.broadcast(packet.senderId, message);
                } else {
                    Channel targetCh = localRegistry.get(receiverId);
                    if (targetCh != null && targetCh.isActive()) {
                        targetCh.writeAndFlush(new TextWebSocketFrame(message));
                    }
                }
            }
        } catch (JsonSyntaxException e) {
            log.warn("Redis onMessage parse/dispatch failed: {}", e.getMessage());
        }
    }
}
