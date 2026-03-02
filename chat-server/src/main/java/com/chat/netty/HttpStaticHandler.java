package com.chat.netty;

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

import java.io.InputStream;

/**
 * 处理 GET 静态页：/、/index.html、/register.html、/admin.html。
 */
public class HttpStaticHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final byte[] INDEX_HTML = loadResource("static/index.html");
    private static final byte[] REGISTER_HTML = loadResource("static/register.html");
    private static final byte[] ADMIN_HTML = loadResource("static/admin.html");

    private static byte[] loadResource(String name) {
        try (InputStream in = HttpStaticHandler.class.getClassLoader().getResourceAsStream(name)) {
            return in != null ? in.readAllBytes() : new byte[0];
        } catch (Exception e) {
            return new byte[0];
        }
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
        byte[] content;
        if ("/".equals(path) || "/index.html".equals(path)) {
            content = INDEX_HTML;
        } else if ("/register.html".equals(path)) {
            content = REGISTER_HTML;
        } else if ("/admin.html".equals(path)) {
            content = ADMIN_HTML;
        } else {
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
                .set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8")
                .set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }
}
