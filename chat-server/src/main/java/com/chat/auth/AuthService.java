package com.chat.auth;

import com.chat.config.AppConfig;
import com.chat.model.User;
import com.chat.neurodb.NeuroDbClient;
import com.google.gson.Gson;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * 登录校验：从 NeuroDB 按用户数字 id 取出 User，校验密码（BCrypt），通过则签发 JWT。
 * 用户数字 id 与 逻辑 id 的映射：A=1001, B=1002, C=1003（与 userKeyNamespace 一致）。
 */
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private final NeuroDbClient neuroDb;
    private final long userKeyNamespace;
    private final JwtUtil jwtUtil;
    private final Gson gson = new Gson();

    public AuthService(NeuroDbClient neuroDb, AppConfig config, JwtUtil jwtUtil) {
        this.neuroDb = neuroDb;
        this.userKeyNamespace = config.getUserKeyNamespace();
        this.jwtUtil = jwtUtil;
    }

    /** 将逻辑 id（如 "A"）转为 NeuroDB 用户 key：简单用 1001+A 的 hash 或固定映射。此处用 1001,1002,1003 对应 A,B,C。 */
    public int userIdToKey(String userId) {
        if (userId == null || userId.isEmpty()) return (int) userKeyNamespace;
        switch (userId.toUpperCase()) {
            case "A": return (int) userKeyNamespace + 1;
            case "B": return (int) userKeyNamespace + 2;
            case "C": return (int) userKeyNamespace + 3;
            default: return (int) (userKeyNamespace + Math.abs(userId.hashCode() % 10000));
        }
    }

    /** 校验账号密码，通过则返回 JWT，否则返回 null。 */
    public String login(String userId, String password) {
        if (userId == null || password == null) return null;
        int key = userIdToKey(userId);
        String json;
        try {
            json = neuroDb.get(key);
        } catch (IOException e) {
            log.warn("NeuroDB get user failed: {}", e.getMessage());
            return null;
        }
        if (json == null || json.isBlank()) return null;
        User user = gson.fromJson(json, User.class);
        if (user == null || !userId.equals(user.getId())) return null;
        if (!BCrypt.checkpw(password, user.getPasswordHash())) return null;
        return jwtUtil.createToken(user.getId());
    }

    /** 用户是否已存在（用于首次部署时按需初始化）。 */
    public boolean userExists(String userId) throws IOException {
        return neuroDb.get(userIdToKey(userId)) != null;
    }

    /** 注册/初始化用户（写入 NeuroDB）。密码会 BCrypt 哈希后存储。 */
    public void initUser(String userId, String plainPassword) throws IOException {
        String hash = BCrypt.hashpw(plainPassword, BCrypt.gensalt(10));
        User user = new User(userId, hash);
        int key = userIdToKey(userId);
        neuroDb.put(key, gson.toJson(user));
    }
}
