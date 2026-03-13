package com.chat.network.ws;

import com.chat.auth.AuthService;
import com.chat.core.ProtocolConsts;
import com.chat.model.User;
import com.chat.network.netty.ChannelRegistry;
import com.chat.protocol.AckPacket;
import com.chat.protocol.ChatMessagePacket;
import com.chat.redis.RedisChatBus;
import com.chat.service.MessageService;
import com.google.gson.Gson;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.io.IOException;
import java.util.Map;

/**
 * 处理 type=chat：校验用户状态 -> MessageService 落库 -> 广播/单发 + ack。
 */
public class ChatMessageHandler implements MessageHandler {
    private static final Gson GSON = new Gson();

    private final AuthService authService;
    private final MessageService messageService;
    private final ChannelRegistry registry;
    private final RedisChatBus redisBus;

    public ChatMessageHandler(AuthService authService, MessageService messageService,
                              ChannelRegistry registry, RedisChatBus redisBus) {
        this.authService = authService;
        this.messageService = messageService;
        this.registry = registry;
        this.redisBus = redisBus;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Map<String, Object> payload, String userId) {
        User sender = null;
        try {
            sender = authService.getUserByUsernameOrId(userId);
        } catch (IOException e) {
            // log in caller if needed
        }
        if (sender != null) {
            if ("MUTED".equals(sender.getStatus())) {
                throw new HandlerException("您已被管理员禁言，无法发送消息");
            }
            if ("BANNED".equals(sender.getStatus())) {
                throw new HandlerException("账号已被封禁");
            }
        }

        MessageService.SendResult result;
        try {
            result = messageService.sendMessage(userId, payload);
        } catch (IOException e) {
            throw new HandlerException("Storage failed");
        }

        ChatMessagePacket packet = result.packet;
        String json = GSON.toJson(packet);

        if (redisBus != null && redisBus.isEnabled()) {
            redisBus.publish(json);
            ctx.writeAndFlush(new TextWebSocketFrame(json));
        } else {
            String receiverId = packet.receiverId;
            if (receiverId == null || receiverId.isEmpty() || ProtocolConsts.TARGET_PUBLIC.equalsIgnoreCase(receiverId)) {
                registry.broadcast(userId, json);
                ctx.writeAndFlush(new TextWebSocketFrame(json));
            } else {
                Channel targetCh = registry.get(receiverId);
                if (targetCh != null && targetCh.isActive()) {
                    targetCh.writeAndFlush(new TextWebSocketFrame(json));
                }
                ctx.writeAndFlush(new TextWebSocketFrame(json));
            }
        }

        String localId = payload != null ? (String) payload.get("localId") : null;
        if (localId != null && !localId.isEmpty()) {
            AckPacket ack = new AckPacket();
            ack.localId = localId;
            ack.messageId = String.valueOf(result.messageId);
            ctx.writeAndFlush(new TextWebSocketFrame(GSON.toJson(ack)));
        }
    }
}
