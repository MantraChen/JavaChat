package com.chat.network.netty;

import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.util.ArrayList;
import java.util.List;
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

    /** 向除 excludeUserId 外的所有已连接用户广播消息（每通道独立帧，确保所有客户端都能收到）。 */
    public void broadcast(String excludeUserId, Object message) {
        String msg = message == null ? "" : (message instanceof String ? (String) message : message.toString());
        for (var e : userIdToChannel.entrySet()) {
            if (excludeUserId != null && excludeUserId.equals(e.getKey())) continue;
            Channel c = e.getValue();
            if (c != null && c.isActive()) c.writeAndFlush(new TextWebSocketFrame(msg));
        }
    }

    public int size() {
        return userIdToChannel.size();
    }

    /** 返回当前在线用户 ID（用户名）列表，用于 @ 提及候选。 */
    public List<String> getOnlineUserIds() {
        return new ArrayList<>(userIdToChannel.keySet());
    }
}
