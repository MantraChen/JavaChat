package com.chat.model;

import com.google.gson.annotations.SerializedName;

/**
 * 用户实体，存入 NeuroDB（Key 为 userId 或 username 映射）。
 * 兼容旧版：id 表示用户名/登录名；userId 为数字主键（新注册用户）。
 */
public class User {
    @SerializedName("userId")
    private long userId;           // 数字主键（新体系）
    @SerializedName("username")
    private String username;       // 登录名，唯一
    @SerializedName("id")
    private String id;             // 兼容旧版，与 username 一致
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

    public User() {}

    /** 旧版兼容：A/B/C 等 */
    public User(String id, String passwordHash) {
        this.id = id;
        this.username = id;
        this.passwordHash = passwordHash;
        this.role = "USER";
        this.status = "APPROVED";
    }

    public User(long userId, String username, String passwordHash, String role, String status) {
        this.userId = userId;
        this.username = username;
        this.id = username;
        this.passwordHash = passwordHash;
        this.role = role != null ? role : "USER";
        this.status = status != null ? status : "PENDING";
    }

    public long getUserId() { return userId; }
    public void setUserId(long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getId() { return id != null ? id : username; }
    public void setId(String id) { this.id = id; }
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
}
