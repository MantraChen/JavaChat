package com.chat;

import com.chat.auth.AuthService;
import com.chat.auth.JwtUtil;
import com.chat.config.AppConfig;
import com.chat.netty.ChannelRegistry;
import com.chat.netty.ChatServerInitializer;
import com.chat.neurodb.NeuroDbClient;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 聊天服务端入口：启动 NeuroDB 客户端、JWT、认证服务、Netty WebSocket 服务。
 * 先启动 NeuroDB（go run cmd/server/main.go），再启动本服务。
 * 可选：首次运行前通过 initUsers() 初始化 A/B/C 账户（需在代码中或配置里打开）。
 */
public class ChatServerMain {
    private static final Logger log = LoggerFactory.getLogger(ChatServerMain.class);

    public static void main(String[] args) throws InterruptedException {
        AppConfig config = new AppConfig();
        NeuroDbClient neuroDb = new NeuroDbClient(config);
        JwtUtil jwtUtil = new JwtUtil(config.getJwtSecret(), config.getJwtExpirationMs());
        AuthService authService = new AuthService(neuroDb, config, jwtUtil);
        ChannelRegistry registry = new ChannelRegistry();

        // 首次部署时若用户不存在则初始化 A/B/C（密码可改为环境变量）
        try {
            for (String id : new String[]{"A", "B", "C"}) {
                if (!authService.userExists(id)) {
                    authService.initUser(id, "pass" + id);
                    log.info("Initialized user {} (password: pass{})", id, id);
                }
            }
        } catch (Exception e) {
            log.warn("Init users check failed: {}", e.getMessage());
        }

        EventLoopGroup boss = new NioEventLoopGroup(1);
        EventLoopGroup worker = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(boss, worker)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChatServerInitializer(authService, jwtUtil, neuroDb, registry))
                    .childOption(ChannelOption.SO_KEEPALIVE, true);

            Channel ch = b.bind(config.getWebsocketPort()).sync().channel();
            log.info("Chat server started: HTTP login on port {}, WebSocket on /ws", config.getWebsocketPort());
            ch.closeFuture().sync();
        } finally {
            boss.shutdownGracefully();
            worker.shutdownGracefully();
        }
    }
}
