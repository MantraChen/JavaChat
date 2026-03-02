package com.chat.netty;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接注册表：userId -> Channel。广播时遍历所有已认证的 Channel 发送。
 */
public class ChannelRegistry {
    private final ConcurrentHashMap<String, Channel> userIdToChannel = new ConcurrentHashMap<>();
    private final ChannelGroup allChannels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    public void register(String userId, Channel ch) {
        Channel old = userIdToChannel.put(userId, ch);
        if (old != null && old.isActive()) {
            old.close();
        }
        allChannels.add(ch);
    }

    public void unregister(String userId) {
        Channel ch = userIdToChannel.remove(userId);
        if (ch != null) allChannels.remove(ch);
    }

    public void unregisterByChannel(Channel ch) {
        userIdToChannel.entrySet().removeIf(e -> e.getValue() == ch);
        allChannels.remove(ch);
    }

    public Channel get(String userId) {
        return userIdToChannel.get(userId);
    }

    /** 向除 excludeUserId 外的所有已连接用户广播消息（以 WebSocket 文本帧发送，否则对方收不到）。 */
    public void broadcast(String excludeUserId, Object message) {
        String msg = message instanceof String ? (String) message : message.toString();
        TextWebSocketFrame frame = new TextWebSocketFrame(msg);
        for (var e : userIdToChannel.entrySet()) {
            if (excludeUserId != null && excludeUserId.equals(e.getKey())) continue;
            Channel c = e.getValue();
            if (c.isActive()) c.writeAndFlush(frame.retain());
        }
        frame.release();
    }

    public int size() {
        return userIdToChannel.size();
    }
}
