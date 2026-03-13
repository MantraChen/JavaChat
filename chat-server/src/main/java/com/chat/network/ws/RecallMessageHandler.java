package com.chat.network.ws;

import com.chat.network.netty.ChannelRegistry;
import com.chat.protocol.RecallPacket;
import com.chat.redis.RedisChatBus;
import com.chat.service.MessageService;
import com.google.gson.Gson;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.util.Map;

/**
 * 处理 type=RECALL：撤回消息并广播 RecallPacket。
 */
public class RecallMessageHandler implements MessageHandler {
    private static final Gson GSON = new Gson();

    private final MessageService messageService;
    private final ChannelRegistry registry;
    private final RedisChatBus redisBus;

    public RecallMessageHandler(MessageService messageService,
                                ChannelRegistry registry, RedisChatBus redisBus) {
        this.messageService = messageService;
        this.registry = registry;
        this.redisBus = redisBus;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Map<String, Object> payload, String userId) {
        Object midObj = payload != null ? payload.get("messageId") : null;
        if (midObj == null) throw new HandlerException("messageId required");
        long messageId = toLong(midObj);
        if (messageId == 0) throw new HandlerException("Invalid messageId");

        RecallPacket recall;
        try {
            recall = messageService.recall(userId, messageId);
        } catch (MessageService.MessageServiceException e) {
            throw new HandlerException(e.getMessage());
        } catch (java.io.IOException e) {
            throw new HandlerException("Recall failed");
        }

        String recallJson = GSON.toJson(recall);
        if (redisBus != null && redisBus.isEnabled()) {
            redisBus.publish(recallJson);
            ctx.writeAndFlush(new TextWebSocketFrame(recallJson));
        } else {
            registry.broadcast(null, recallJson);
            ctx.writeAndFlush(new TextWebSocketFrame(recallJson));
        }
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0L; }
        }
        return 0L;
    }
}
