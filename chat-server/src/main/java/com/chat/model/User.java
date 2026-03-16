package com.chat.model;

import com.google.gson.annotations.SerializedName;

/**
 * 用户实体，存入 NeuroDB（Key 为 userId；username 经哈希映射到 userId）。
 */
public class User {
    @SerializedName("userId")
    private long userId;
    @SerializedName("username")
    private String username;       // 登录名，唯一
    @SerializedName("password_hash")
    private String passwordHash;
    @SerializedName("role")
    private String role = "USER";  // USER, ADMIN
    @SerializedName("status")
    private String status = "PENDING"; // PENDING, APPROVED, REJECTED, MUTED, BANNED
    @SerializedName("nickname")
    private String nickname;
    @SerializedName("avatarUrl")
    private String avatarUrl;
    @SerializedName("muteUntil")
    private Long muteUntil;
    @SerializedName("banUntil")
    private Long banUntil;
    /** 每次封禁或修改密码时自增，JWT 携带此值；校验时与 DB 不一致则拒绝（Token 失效）。 */
    @SerializedName("tokenVersion")
    private long tokenVersion = 0;
    /** 上次活跃时间（断开连接时更新），毫秒时间戳；0 表示从未记录。 */
    @SerializedName("lastSeenAt")
    private long lastSeenAt = 0;

    public User() {}

    public User(long userId, String username, String passwordHash, String role, String status) {
        this.userId = userId;
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role != null ? role : "USER";
        this.status = status != null ? status : "PENDING";
    }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }
    public String getAvatarUrl() { return avatarUrl; }
    public void setAvatarUrl(String avatarUrl) { this.avatarUrl = avatarUrl; }
    public Long getMuteUntil() { return muteUntil; }
    public void setMuteUntil(Long muteUntil) { this.muteUntil = muteUntil; }
    public Long getBanUntil() { return banUntil; }
    public void setBanUntil(Long banUntil) { this.banUntil = banUntil; }
    public long getTokenVersion() { return tokenVersion; }
    public void setTokenVersion(long tokenVersion) { this.tokenVersion = tokenVersion; }
    public long getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(long lastSeenAt) { this.lastSeenAt = lastSeenAt; }
}
