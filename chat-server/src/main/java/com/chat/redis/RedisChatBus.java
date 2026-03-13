package com.chat.redis;

import com.chat.network.netty.ChannelRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

/**
 * Redis 消息总线：发布聊天/撤回消息到 channel:chat_messages，
 * 并在后台线程中订阅该频道，由 RedisChatSubscriber 在本节点投递。
 * 若 host 为空则不启用，单机模式无需 Redis。
 */
public class RedisChatBus {
    private static final Logger log = LoggerFactory.getLogger(RedisChatBus.class);
    public static final String CHANNEL = "channel:chat_messages";

    private final String host;
    private final int port;
    private final ChannelRegistry localRegistry;

    private Jedis publishJedis;
    private Thread subscriberThread;
    private volatile boolean running;

    public RedisChatBus(String host, int port, ChannelRegistry localRegistry) {
        this.host = host == null ? "" : host.trim();
        this.port = port <= 0 ? 6379 : port;
        this.localRegistry = localRegistry;
    }

    public boolean isEnabled() {
        return !host.isEmpty();
    }

    /**
     * 发布一条消息到 Redis，所有订阅节点都会收到并在本地投递。
     */
    public void publish(String message) {
        if (!isEnabled() || message == null) return;
        try {
            Jedis j = getPublishJedis();
            if (j != null) {
                j.publish(CHANNEL, message);
                log.trace("Redis published to {}", CHANNEL);
            }
        } catch (Exception e) {
            log.warn("Redis publish failed: {}", e.getMessage());
        }
    }

    private synchronized Jedis getPublishJedis() {
        if (publishJedis == null && isEnabled()) {
            try {
                publishJedis = new Jedis(host, port);
            } catch (Exception e) {
                log.warn("Redis publish client connect failed: {}", e.getMessage());
            }
        }
        return publishJedis;
    }

    /**
     * 启动后台订阅线程（阻塞在 subscribe 上）。
     */
    public void start() {
        if (!isEnabled()) return;
        running = true;
        subscriberThread = new Thread(() -> {
            Jedis subJedis = null;
            try {
                subJedis = new Jedis(host, port);
                subJedis.subscribe(new RedisChatSubscriber(localRegistry), CHANNEL);
            } catch (Exception e) {
                if (running) log.warn("Redis subscriber error: {}", e.getMessage());
            } finally {
                if (subJedis != null) try { subJedis.close(); } catch (Exception ignored) {}
            }
        }, "redis-chat-subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
        log.info("Redis chat subscriber started: {}:{}", host, port);
    }

    public void stop() {
        running = false;
        if (publishJedis != null) {
            try { publishJedis.close(); } catch (Exception ignored) {}
            publishJedis = null;
        }
        if (subscriberThread != null && subscriberThread.isAlive()) {
            subscriberThread.interrupt();
        }
    }
}
