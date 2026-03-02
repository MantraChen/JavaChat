package com.chat.auth;

import com.chat.config.AppConfig;
import com.chat.model.User;
import com.chat.neurodb.NeuroDbClient;
import com.chat.util.SnowflakeId;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 账户：注册（PENDING）、登录（仅 APPROVED）、管理员审批。
 * NeuroDB Key：1=待审核列表JSON；用户名哈希->userId；userId->UserData JSON。
 */
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int KEY_PENDING_LIST = 1;
    private static final int USERNAME_MAP_NS = 100_000_000;
    private static final int USER_ID_START = 2_000_000;
    private static final Type LIST_LONG = new TypeToken<ArrayList<Long>>() {}.getType();

    private final NeuroDbClient neuroDb;
    private final long userKeyNamespace;
    private final JwtUtil jwtUtil;
    private final SnowflakeId snowflakeId = new SnowflakeId();
    private final Gson gson = new Gson();

    public AuthService(NeuroDbClient neuroDb, AppConfig config, JwtUtil jwtUtil) {
        this.neuroDb = neuroDb;
        this.userKeyNamespace = config.getUserKeyNamespace();
        this.jwtUtil = jwtUtil;
    }

    private static int usernameToKey(String username) {
        int h = username != null ? username.hashCode() : 0;
        return USERNAME_MAP_NS + (Math.abs(h) % 50_000_000);
    }

    /** 新用户 ID（int 兼容 NeuroDB key），从 USER_ID_START 起。 */
    private int nextUserId() throws IOException {
        String raw = neuroDb.get(0);
        int next = (raw != null && !raw.isEmpty()) ? Integer.parseInt(raw.trim()) : USER_ID_START;
        neuroDb.put(0, String.valueOf(next + 1));
        return next;
    }

    /** 注册：写入用户（status=PENDING），并加入待审核列表。 */
    public String register(String username, String password) throws IOException {
        if (username == null || (username = username.trim()).isEmpty() || password == null || password.isEmpty())
            return "用户名或密码为空";
        int nameKey = usernameToKey(username);
        if (neuroDb.get(nameKey) != null)
            return "用户名已存在";
        int userId = nextUserId();
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));
        User user = new User(userId, username, hash, "USER", "PENDING");
        neuroDb.put(userId, gson.toJson(user));
        neuroDb.put(nameKey, String.valueOf(userId));
        List<Long> pending = getPendingUserIds();
        pending.add((long) userId);
        neuroDb.put(KEY_PENDING_LIST, gson.toJson(pending));
        log.info("Registered user {} (userId={}), status=PENDING", username, userId);
        return null;
    }

    @SuppressWarnings("unchecked")
    public List<Long> getPendingUserIds() throws IOException {
        String json = neuroDb.get(KEY_PENDING_LIST);
        if (json == null || json.isBlank()) return new ArrayList<>();
        List<Long> list = gson.fromJson(json, LIST_LONG);
        return list != null ? list : new ArrayList<>();
    }

    /** 按 userId 取用户（key 即 userId）。 */
    public User getUserByUserId(int userId) throws IOException {
        String json = neuroDb.get(userId);
        if (json == null || json.isBlank()) return null;
        return gson.fromJson(json, User.class);
    }

    /** 审批通过：更新 status=APPROVED，从待审核列表移除。 */
    public void approve(int userId) throws IOException {
        User u = getUserByUserId(userId);
        if (u == null) return;
        u.setStatus("APPROVED");
        neuroDb.put(userId, gson.toJson(u));
        List<Long> pending = getPendingUserIds();
        pending.remove((Long) (long) userId);
        neuroDb.put(KEY_PENDING_LIST, gson.toJson(pending));
        log.info("Approved userId={}", userId);
    }

    /** 拒绝：status=REJECTED，从待审核列表移除。 */
    public void reject(int userId) throws IOException {
        User u = getUserByUserId(userId);
        if (u == null) return;
        u.setStatus("REJECTED");
        neuroDb.put(userId, gson.toJson(u));
        List<Long> pending = getPendingUserIds();
        pending.remove((Long) (long) userId);
        neuroDb.put(KEY_PENDING_LIST, gson.toJson(pending));
        log.info("Rejected userId={}", userId);
    }

    /** 登录：支持旧版 A/B/C 或新体系 username。返回 JWT；失败返回 null。需 APPROVED 才能登录。 */
    public String login(String username, String password) {
        if (username == null || password == null) return null;
        username = username.trim();
        // 旧版 A/B/C
        int legacyKey = userIdToKey(username);
        String json;
        try {
            json = neuroDb.get(legacyKey);
        } catch (IOException e) {
            log.warn("NeuroDB get user failed: {}", e.getMessage());
            return null;
        }
        if (json != null && !json.isBlank()) {
            User user = gson.fromJson(json, User.class);
            if (user != null && username.equals(user.getId()) && BCrypt.checkpw(password, user.getPasswordHash())) {
                if ("REJECTED".equals(user.getStatus())) return null;
                return jwtUtil.createToken(user.getId(), user != null && user.getRole() != null ? user.getRole() : "USER");
            }
        }
        // 新体系：username -> userId -> User
        int nameKey = usernameToKey(username);
        try {
            String idStr = neuroDb.get(nameKey);
            if (idStr == null || idStr.isBlank()) return null;
            int userId = Integer.parseInt(idStr.trim());
            User user = getUserByUserId(userId);
            if (user == null || !username.equals(user.getUsername())) return null;
            if (!"APPROVED".equals(user.getStatus())) return null;
            if (!BCrypt.checkpw(password, user.getPasswordHash())) return null;
            return jwtUtil.createToken(user.getUsername(), user.getRole());
        } catch (IOException e) {
            log.warn("Login lookup failed: {}", e.getMessage());
            return null;
        }
    }

    /** 旧版：逻辑 id 对应 key（A=1000001 等）。 */
    public int userIdToKey(String userId) {
        if (userId == null || userId.isEmpty()) return (int) userKeyNamespace;
        switch (userId.toUpperCase()) {
            case "A": return (int) userKeyNamespace + 1;
            case "B": return (int) userKeyNamespace + 2;
            case "C": return (int) userKeyNamespace + 3;
            default: return (int) (userKeyNamespace + Math.abs(userId.hashCode() % 10000));
        }
    }

    public boolean userExists(String username) throws IOException {
        if (username == null) return false;
        if ("A".equalsIgnoreCase(username) || "B".equalsIgnoreCase(username) || "C".equalsIgnoreCase(username))
            return neuroDb.get(userIdToKey(username)) != null;
        return neuroDb.get(usernameToKey(username)) != null;
    }

    /** 仅用于兼容旧版初始化 A/B/C。 */
    public void initUser(String id, String plainPassword) throws IOException {
        String hash = BCrypt.hashpw(plainPassword, BCrypt.gensalt(10));
        User user = new User(id, hash);
        int key = userIdToKey(id);
        neuroDb.put(key, gson.toJson(user));
    }

    /** 初始化管理员账号（username=admin, 需 APPROVED）。 */
    public void initAdminIfAbsent(String adminUsername, String password) throws IOException {
        int nameKey = usernameToKey(adminUsername);
        if (neuroDb.get(nameKey) != null) return;
        int userId = nextUserId();
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));
        User admin = new User(userId, adminUsername, hash, "ADMIN", "APPROVED");
        neuroDb.put(userId, gson.toJson(admin));
        neuroDb.put(nameKey, String.valueOf(userId));
        log.info("Initialized admin user {}", adminUsername);
    }
}
