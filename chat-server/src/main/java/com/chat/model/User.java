package com.chat.model;

import com.google.gson.annotations.SerializedName;

/**
 * 用户实体，存入 NeuroDB 的 value（JSON）。
 * Key 为 userKeyNamespace + 数字 id（如 1000001 = A）。
 */
public class User {
    @SerializedName("id")
    private String id;           // 唯一标识，如 "A", "B", "C"
    @SerializedName("password_hash")
    private String passwordHash; // BCrypt 哈希

    public User() {}

    public User(String id, String passwordHash) {
        this.id = id;
        this.passwordHash = passwordHash;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
}
