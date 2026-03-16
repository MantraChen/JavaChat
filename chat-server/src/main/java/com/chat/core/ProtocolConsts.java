package com.chat.core;

/**
 * WebSocket 与协议层常量，消除魔法字符串。
 */
public final class ProtocolConsts {

    // ---------- WebSocket 消息 type ----------
    public static final String TYPE_AUTH = "auth";
    public static final String TYPE_CHAT = "chat";
    public static final String TYPE_SYNC = "sync";
    public static final String TYPE_SYNC_RESULT = "sync_result";
    public static final String TYPE_RECALL = "RECALL";
    public static final String TYPE_TYPING = "typing";
    public static final String TYPE_SYSTEM = "system";

    // ---------- 特殊发送者 ----------
    public static final String SENDER_SYSTEM = "SYSTEM";

    // ---------- 信箱/目标 ----------
    /** 大厅或公聊：receiverId/target 为空或此值 */
    public static final String TARGET_PUBLIC = "PUBLIC";
    /** 私信信箱：sync 结果 target 表示当前用户收件箱 */
    public static final String TARGET_INBOX = "INBOX";

    // ---------- HTTP API 路径（供 HttpDispatcher 路由） ----------
    public static final String API_LOGIN = "/api/login";
    public static final String API_REGISTER = "/api/register";
    public static final String API_UPLOAD = "/api/upload";
    public static final String API_ONLINE = "/api/online";
    public static final String API_USERS = "/api/users";
    public static final String API_ADMIN_USERS = "/api/admin/users";
    public static final String API_ADMIN_APPROVE = "/api/admin/approve";
    public static final String API_ADMIN_REJECT = "/api/admin/reject";
    public static final String API_ADMIN_ACTION = "/api/admin/action";
    public static final String API_ADMIN_ALL_USERS = "/api/admin/all-users";
    public static final String API_USER_PROFILE = "/api/user/profile";
    public static final String API_USER_PASSWORD = "/api/user/password";
    public static final String API_USER_DELETE = "/api/user/delete";
    public static final String API_ADMIN_BROADCAST = "/api/admin/broadcast";
    public static final String API_ADMIN_MESSAGE_RECALL = "/api/admin/message/recall";
    public static final String FILES_PREFIX = "/files/";

    private ProtocolConsts() {}
}
