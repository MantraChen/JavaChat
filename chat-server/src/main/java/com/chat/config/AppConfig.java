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
    private final long messageKeyMin = 2_000_000_000_000L;
    private final long messageKeyMax = 9_000_000_000_000L;
    /** 首次启动时创建的管理员账号（若不存在） */
    private String adminUsername = "admin";
    private String adminPassword = "admin123";
    /** Redis 地址：为空或 null 时不启用，单机模式；设置后启用 Pub/Sub 跨节点路由 */
    private String redisHost = "";
    private int redisPort = 6379;
    /** 图片上传保存目录（相对或绝对路径），GET /files/xxx 从此目录提供 */
    private String uploadDir = "upload";

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
    public String getRedisHost() { return redisHost; }
    public void setRedisHost(String redisHost) { this.redisHost = redisHost; }
    public int getRedisPort() { return redisPort; }
    public void setRedisPort(int redisPort) { this.redisPort = redisPort; }
    /** 是否启用 Redis 跨节点消息总线 */
    public boolean isRedisEnabled() { return redisHost != null && !redisHost.isBlank(); }
    public String getUploadDir() { return uploadDir; }
    public void setUploadDir(String uploadDir) { this.uploadDir = uploadDir; }
}
