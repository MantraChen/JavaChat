package com.chat.network.ws;

import com.chat.service.MessageService;
import com.google.gson.Gson;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.io.IOException;
import java.util.Map;

/**
 * 处理 type=sync：按 target(PUBLIC/INBOX) 与 lastTimestamp 拉取增量，回写 sync_result。
 */
public class SyncMessageHandler implements MessageHandler {
    private static final Gson GSON = new Gson();

    private final MessageService messageService;

    public SyncMessageHandler(MessageService messageService) {
        this.messageService = messageService;
    }

    @Override
    public void handle(ChannelHandlerContext ctx, Map<String, Object> payload, String userId) {
        String target = payload != null ? (String) payload.get("target") : null;
        Object tsObj = payload != null ? payload.get("lastTimestamp") : null;
        long lastTs = tsObj instanceof Number ? ((Number) tsObj).longValue() : 0L;

        MessageService.SyncResult result;
        try {
            result = messageService.sync(userId, target, lastTs);
        } catch (IOException e) {
            throw new HandlerException("Sync failed");
        }
        ctx.writeAndFlush(new TextWebSocketFrame(GSON.toJson(result.packet)));
    }
}
