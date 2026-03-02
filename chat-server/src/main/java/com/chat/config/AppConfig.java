package com.chat.config;

/**
 * 应用配置：NeuroDB 地址、JWT 密钥、端口等。
 * 可通过环境变量或配置文件覆盖。
 */
public class AppConfig {
    private String neuroDbHttpUrl = "http://127.0.0.1:8080";
    private int websocketPort = 9091;
    private String jwtSecret = "chat-server-jwt-secret-change-in-production";
    private long jwtExpirationMs = 7 * 24 * 60 * 60 * 1000L; // 7 days
    /** 用户数据在 NeuroDB 中的 key 命名空间：用户 key = USER_NS + userId 的 hash/numeric id */
    private long userKeyNamespace = 1_000_000L;
    /** 消息 key 使用 Snowflake，需保证在 2^63 内，此处仅做范围扫描用 */
    private long messageKeyMin = 2_000_000_000_000L;
    private long messageKeyMax = 9_000_000_000_000L;
    /** 首次启动时创建的管理员账号（若不存在） */
    private String adminUsername = "admin";
    private String adminPassword = "admin123";

    public String getNeuroDbHttpUrl() { return neuroDbHttpUrl; }
    public void setNeuroDbHttpUrl(String neuroDbHttpUrl) { this.neuroDbHttpUrl = neuroDbHttpUrl; }
    public int getWebsocketPort() { return websocketPort; }
    public void setWebsocketPort(int websocketPort) { this.websocketPort = websocketPort; }
    public String getJwtSecret() { return jwtSecret; }
    public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }
    public long getJwtExpirationMs() { return jwtExpirationMs; }
    public void setJwtExpirationMs(long jwtExpirationMs) { this.jwtExpirationMs = jwtExpirationMs; }
    public long getUserKeyNamespace() { return userKeyNamespace; }
    public void setUserKeyNamespace(long userKeyNamespace) { this.userKeyNamespace = userKeyNamespace; }
    public long getMessageKeyMin() { return messageKeyMin; }
    public long getMessageKeyMax() { return messageKeyMax; }
    public String getAdminUsername() { return adminUsername; }
    public void setAdminUsername(String adminUsername) { this.adminUsername = adminUsername; }
    public String getAdminPassword() { return adminPassword; }
    public void setAdminPassword(String adminPassword) { this.adminPassword = adminPassword; }
}
