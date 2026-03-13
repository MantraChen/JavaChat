package com.chat.network.ws;

import io.netty.channel.ChannelHandlerContext;

import java.util.Map;

/**
 * WebSocket 消息 type 的策略接口：chat / sync / RECALL / typing 等由各自实现类处理。
 */
public interface MessageHandler {
    void handle(ChannelHandlerContext ctx, Map<String, Object> payload, String userId);
}
