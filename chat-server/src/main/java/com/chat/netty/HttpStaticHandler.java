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
 * 处理 GET / 或 GET /index.html，返回 src/main/resources/static/index.html，
 * 用于一体化部署时在浏览器中直接打开聊天界面。
 */
public class HttpStaticHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final byte[] INDEX_HTML = loadIndexHtml();

    private static byte[] loadIndexHtml() {
        try (InputStream in = HttpStaticHandler.class.getClassLoader()
                .getResourceAsStream("static/index.html")) {
            if (in == null) return new byte[0];
            return in.readAllBytes();
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
        if (!"/".equals(path) && !"/index.html".equals(path)) {
            ctx.fireChannelRead(req.retain());
            return;
        }
        if (INDEX_HTML.length == 0) {
            req.release();
            ByteBuf body = Unpooled.copiedBuffer("static/index.html not found", CharsetUtil.UTF_8);
            DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND, body);
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=utf-8")
                    .set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
            ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            return;
        }
        req.release();
        ByteBuf body = Unpooled.copiedBuffer(INDEX_HTML);
        DefaultFullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, body);
        resp.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=utf-8")
                .set(HttpHeaderNames.CONTENT_LENGTH, body.readableBytes());
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }
}
