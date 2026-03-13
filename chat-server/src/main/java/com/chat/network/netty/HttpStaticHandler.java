package com.chat.network.netty;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/**
 * 处理 GET 静态页：/、/index.html、/register.html、/admin.html 及 /assets/*（Vite 构建产物）。
 * 开发时优先从磁盘读取，便于改完刷新即生效，无需重启服务。
 */
public class HttpStaticHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final byte[] INDEX_HTML = loadResource("static/index.html");
    private static final byte[] REGISTER_HTML = loadResource("static/register.html");
    private static final byte[] ADMIN_HTML = loadResource("static/admin.html");

    /** 开发时从磁盘读静态文件（cwd 为 chat-server 或项目根） */
    private static byte[] loadFromDisk(String relativePath) {
        for (String base : new String[] { "src/main/resources", "chat-server/src/main/resources" }) {
            Path p = Paths.get(base, relativePath).toAbsolutePath().normalize();
            try {
                if (Files.isRegularFile(p)) return Files.readAllBytes(p);
            } catch (IOException ignored) { }
        }
        return null;
    }

    private static byte[] loadResource(String name) {
        byte[] fromDisk = loadFromDisk(name);
        if (fromDisk != null && fromDisk.length > 0) return fromDisk;
        try (InputStream in = HttpStaticHandler.class.getClassLoader().getResourceAsStream(name)) {
            return in != null ? in.readAllBytes() : new byte[0];
        } catch (Exception e) {
            return new byte[0];
        }
    }

    /** 按请求从磁盘或缓存取内容 */
    private static byte[] getContent(String path) {
        String name = path.equals("/") || path.equals("/index.html") ? "static/index.html"
                : path.equals("/register.html") ? "static/register.html"
                : path.equals("/admin.html") ? "static/admin.html"
                : path.startsWith("/assets/") ? "static" + path : null;
        if (name == null) return null;
        byte[] fromDisk = loadFromDisk(name);
        if (fromDisk != null && fromDisk.length > 0) return fromDisk;
        if ("/".equals(path) || "/index.html".equals(path)) return INDEX_HTML;
        if ("/register.html".equals(path)) return REGISTER_HTML;
        if ("/admin.html".equals(path)) return ADMIN_HTML;
        if (path.startsWith("/assets/")) {
            try (InputStream in = HttpStaticHandler.class.getClassLoader().getResourceAsStream(name)) {
                return in != null ? in.readAllBytes() : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    private static String contentTypeForPath(String path) {
        if (path.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (path.endsWith(".css")) return "text/css; charset=utf-8";
        return "text/html; charset=utf-8";
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest)) {
            ctx.fireChannelRead(msg);
            return;
        }
        channelRead0(ctx, (FullHttpRequest) msg);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        if (!req.method().equals(io.netty.handler.codec.http.HttpMethod.GET)) {
            ctx.fireChannelRead(req.retain());
            return;
        }
        String uri = req.uri();
        if (uri == null) uri = "";
        int q = uri.indexOf('?');
        String path = q >= 0 ? uri.substring(0, q) : uri;
        byte[] content = getContent(path);
        if (content == null) {
            ctx.fireChannelRead(req.retain());
            return;
        }
        req.release();
        if (content.length == 0) {
            ByteBuf body = Unpooled.copiedBuffer("Not found", CharsetUtil.UTF_8);
            DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, body);
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
                    .set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        ByteBuf body = Unpooled.copiedBuffer(content);
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, body);
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, contentTypeForPath(path))
                .set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }
}
