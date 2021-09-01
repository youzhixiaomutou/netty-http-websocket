package me.linxiaowei.netty.websocket;

import com.alibaba.fastjson.JSONObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslHandler;

import java.nio.charset.StandardCharsets;

/**
 * WebSocketHandler
 * 实现思路： 将 websocket 收到的信息按照规定的格式转化成 HTTP 请求信息，之后转发给 HTTP 请求 的 handler 处理即可。
 * 这样几乎不需要什么编码量，就可以实现从 WebSocket 请求转发到 HTTP 处理的编码
 * <p>
 * 消息格式（JSON）： uri、method、header、content
 * 举例：
 * {"uri":"/roleServer","method":"GET","header":{"NDAPI-InterfaceUserName":"qaceshi","NDAPI-TimeStamp":"20210615110400","NDAPI-CheckCode":"f442c48b6533f57c0e79dc7373d2c3d2"},"content":{"GameId":"1","UserName":"hj710"}}
 *
 * @author <a href="mailto://linxiaowei.me@gmail.com">LinXiaoWei</a>
 * @date 2021/8/31 14:08
 */
public class WebSocketHandler extends SimpleChannelInboundHandler<Object> {

    private WebSocketServerHandshaker handshaker;

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof FullHttpRequest) {
            // HTTP 接入
            handlerHttpRequest(ctx, (FullHttpRequest) msg);
        } else if (msg instanceof WebSocketFrame) {
            // WebSocket 接入
            handlerWebSocket(ctx, (WebSocketFrame) msg);
        }
    }

    private void handlerHttpRequest(ChannelHandlerContext ctx, FullHttpRequest req) {
        // 如果 HTTP 解码失败，返回 HTTP 异常
        if (!req.decoderResult().isSuccess() || !"websocket".equals(req.headers().get("Upgrade"))) {
            // 如果不是 Upgrade 则传递到后面的 HTTP Handler 来处理
            ctx.fireChannelRead(req.retain());
            return;
        }
        // 构造握手响应返回
        WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                null != ctx.pipeline().get(SslHandler.class) ? "wss" : "ws" + "://" + req.headers().get(HttpHeaderNames.HOST),
                null, false);
        handshaker = wsFactory.newHandshaker(req);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            handshaker.handshake(ctx.channel(), req);
        }
    }

    private void handlerWebSocket(ChannelHandlerContext ctx, WebSocketFrame frame) {
        // 判断是否是关闭链路的命令
        if (frame instanceof CloseWebSocketFrame) {
            handshaker.close(ctx.channel(), ((CloseWebSocketFrame) frame).retain());
            return;
        }
        // 判断是否是 ping 消息
        if (frame instanceof PingWebSocketFrame) {
            ctx.channel().write(new PongWebSocketFrame(frame.content().retain()));
        }
        // 本例程只支持文本消息，不支持二进制消息
        if (!(frame instanceof TextWebSocketFrame)) {
            throw new UnsupportedOperationException(String.format("%s frame types not supported", frame.getClass().getName()));
        }
        JSONObject reqJsonObject = JSONObject.parseObject(((TextWebSocketFrame) frame).text());
        String uri = reqJsonObject.getString("uri");
        String method = reqJsonObject.getString("method").toUpperCase();
        JSONObject headerJsonObject = reqJsonObject.getJSONObject("header");
        ByteBuf content = Unpooled.copiedBuffer(reqJsonObject.getString("content"), StandardCharsets.UTF_8);
        FullHttpRequest req = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, new HttpMethod(method), uri, content);
        for (String key : headerJsonObject.keySet()) {
            req.headers().set(key, headerJsonObject.getString(key));
        }
        // 用来标识是否是 WebSocket 转发的，向后传递
        req.headers().set("ws", true);
        ctx.fireChannelRead(req.retain());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        super.exceptionCaught(ctx, cause);
    }

}
