package com.chat.network.ws;

/**
 * WebSocket 业务处理中的可预期错误（如禁言、撤回超时），由上层统一回写 error 包并可选关闭连接。
 */
public class HandlerException extends RuntimeException {
    public HandlerException(String message) {
        super(message);
    }
}
