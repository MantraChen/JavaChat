package com.chat.auth;

import com.chat.config.AppConfig;
import com.chat.model.User;
import com.chat.neurodb.NeuroDbClient;
import com.chat.util.SnowflakeId;
import com.google.gson.Gson;

import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.common.hash.Hashing;
import com.google.gson.JsonSyntaxException;

/**
 * 账户：注册（PENDING）、登录（仅 APPROVED）、管理员审批。
 * NeuroDB Key：待审核用 PENDING_NS 区间 Scan；用户名哈希->userId；userId->UserData JSON。
 */
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    /** 待审核用户 key 区间 [PENDING_NS, PENDING_NS + PENDING_RANGE)，单 key  per userId，value 非 REMOVED 即待审核。 */
    private static final long PENDING_NS = 200_000_000L;
    private static final long PENDING_RANGE = 100_000_000L;
    private static final String PENDING_TOMBSTONE = "REMOVED";
    private static final int USERNAME_MAP_NS = 100_000_000;
    private static final int USER_ID_START = 2_000_000;

    private final NeuroDbClient neuroDb;
    private final long userKeyNamespace;
    private final JwtUtil jwtUtil;
    private final Gson gson = new Gson();
    private final SnowflakeId snowflakeId = new SnowflakeId();

    public AuthService(NeuroDbClient neuroDb, AppConfig config, JwtUtil jwtUtil) {
        this.neuroDb = neuroDb;
        this.userKeyNamespace = config.getUserKeyNamespace();
        this.jwtUtil = jwtUtil;
    }

    /** 使用 64 位 MurmurHash3 生成用户名映射 Key，避免 String.hashCode() 碰撞导致覆盖。 */
    private static long usernameToKey(String username) {
        if (username == null || username.isEmpty()) return USERNAME_MAP_NS;
        long h = Hashing.murmur3_128().hashString(username, StandardCharsets.UTF_8).asLong();
        if (h < 0) h = -h;
        return h < USERNAME_MAP_NS ? USERNAME_MAP_NS + (h % (Long.MAX_VALUE - USERNAME_MAP_NS)) : h;
    }

    /** 使用雪花算法生成唯一 userId，落在 [USER_ID_START, USERNAME_MAP_NS) 以支持按范围扫描用户；碰撞时重试。 */
    private long nextUserId() throws IOException {
        long range = USERNAME_MAP_NS - USER_ID_START;
        for (;;) {
            long id = USER_ID_START + (Math.abs(snowflakeId.nextId()) % range);
            if (neuroDb.get(id) == null) return id;
        }
    }

    /** 注册：写入用户（status=PENDING），并在待审核区间写入单条 key，无读-改-写。 */
    public String register(String username, String password) throws IOException {
        if (username == null || (username = username.trim()).isEmpty() || password == null || password.isEmpty())
            return "用户名或密码为空";
        long nameKey = usernameToKey(username);
        if (neuroDb.get(nameKey) != null)
            return "用户名已存在";
        long userId = nextUserId();
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));
        User user = new User(userId, username, hash, "USER", "PENDING");
        neuroDb.put(userId, gson.toJson(user));
        neuroDb.put(nameKey, String.valueOf(userId));
        addPending(userId);
        log.info("Registered user {} (userId={}), status=PENDING", username, userId);
        return null;
    }

    private void addPending(long userId) throws IOException {
        neuroDb.put(PENDING_NS + userId, "1");
    }

    private void removePending(long userId) throws IOException {
        neuroDb.put(PENDING_NS + userId, PENDING_TOMBSTONE);
    }

    /** 通过范围 Scan 待审核区间得到待审核 userId 列表，无大 JSON 并发覆盖。 */
    public List<Long> getPendingUserIds() throws IOException {
        List<Long> out = new ArrayList<>();
        for (NeuroDbClient.ScanRecord rec : neuroDb.scan(PENDING_NS, PENDING_NS + PENDING_RANGE)) {
            if (rec.value == null || PENDING_TOMBSTONE.equals(rec.value)) continue;
            out.add(rec.key - PENDING_NS);
        }
        return out;
    }

    /** 按 userId 取用户（key 即 userId）。 */
    public User getUserByUserId(long userId) throws IOException {
        String json = neuroDb.get(userId);
        if (json == null || json.isBlank()) return null;
        return gson.fromJson(json, User.class);
    }

    /** 按用户名或数字 userId 取用户（JWT subject 可能是 username 或旧版 A/B/C）。 */
    public User getUserByUsernameOrId(String usernameOrId) throws IOException {
        if (usernameOrId == null || usernameOrId.trim().isEmpty()) return null;
        String s = usernameOrId.trim();
        try {
            long userId = Long.parseLong(s);
            if (userId >= USER_ID_START && userId < USERNAME_MAP_NS) {
                User u = getUserByUserId(userId);
                if (u != null) return u;
            }
        } catch (NumberFormatException ignored) {}
        int legacyKey = userIdToKey(s);
        String json = neuroDb.get((long) legacyKey);
        if (json != null && !json.isBlank()) {
            User u = gson.fromJson(json, User.class);
            if (u != null && s.equals(u.getId())) return u;
        }
        long nameKey = usernameToKey(s);
        String idStr = neuroDb.get(nameKey);
        if (idStr == null || idStr.isBlank()) return null;
        try {
            long userId = Long.parseLong(idStr.trim());
            return getUserByUserId(userId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 扫描用户 key 范围，返回所有状态为 APPROVED 的用户（通讯录用）。 */
    public List<User> getAllApprovedUsers() throws IOException {
        List<User> out = new ArrayList<>();
        for (NeuroDbClient.ScanRecord rec : neuroDb.scan(USER_ID_START, USERNAME_MAP_NS)) {
            if (rec.value == null || rec.value.isBlank() || !rec.value.trim().startsWith("{")) continue;
            try {
                User u = gson.fromJson(rec.value, User.class);
                if (u != null && "APPROVED".equals(u.getStatus())) out.add(u);
            } catch (JsonSyntaxException ignored) {}
        }
        return out;
    }

    /** 扫描用户 key 范围，返回所有用户（管理后台用，含 status）。 */
    public List<User> getAllUsers() throws IOException {
        List<User> out = new ArrayList<>();
        for (NeuroDbClient.ScanRecord rec : neuroDb.scan(USER_ID_START, USERNAME_MAP_NS)) {
            if (rec.value == null || rec.value.isBlank() || !rec.value.trim().startsWith("{")) continue;
            try {
                User u = gson.fromJson(rec.value, User.class);
                if (u != null) out.add(u);
            } catch (JsonSyntaxException ignored) {}
        }
        return out;
    }

    /** 通用状态修改：MUTED / BANNED / APPROVED 等，仅更新 NeuroDB 中的用户 JSON。 */
    public void changeUserStatus(long userId, String newStatus) throws IOException {
        changeUserStatusWithDuration(userId, newStatus, 0);
    }

    /** 带时效的状态修改：until=0 表示永久。 */
    public void changeUserStatusWithDuration(long userId, String newStatus, long until) throws IOException {
        User u = getUserByUserId(userId);
        if (u == null) return;
        u.setStatus(newStatus);
        if ("MUTED".equals(newStatus)) {
            u.setMuteUntil(until > 0 ? until : null);
            u.setBanUntil(null);
        } else if ("BANNED".equals(newStatus)) {
            u.setBanUntil(until > 0 ? until : null);
            u.setMuteUntil(null);
        } else {
            u.setMuteUntil(null);
            u.setBanUntil(null);
        }
        neuroDb.put(userId, gson.toJson(u));
        log.info("Changed userId={} status to {} until={}", userId, newStatus, until);
    }

    /** 检查并自动解除过期的禁言/封禁状态，返回是否仍被限制。 */
    public boolean checkAndAutoUnlock(User u) throws IOException {
        if (u == null) return false;
        long now = System.currentTimeMillis();
        if ("MUTED".equals(u.getStatus()) && u.getMuteUntil() != null && now > u.getMuteUntil()) {
            changeUserStatusWithDuration(u.getUserId(), "APPROVED", 0);
            return false;
        }
        if ("BANNED".equals(u.getStatus()) && u.getBanUntil() != null && now > u.getBanUntil()) {
            changeUserStatusWithDuration(u.getUserId(), "APPROVED", 0);
            return false;
        }
        return "MUTED".equals(u.getStatus()) || "BANNED".equals(u.getStatus());
    }

    /** 审批通过：更新 status=APPROVED，从待审核区间移除（单 key 写入，无读-改-写）。 */
    public void approve(long userId) throws IOException {
        User u = getUserByUserId(userId);
        if (u == null) return;
        u.setStatus("APPROVED");
        neuroDb.put(userId, gson.toJson(u));
        removePending(userId);
        log.info("Approved userId={}", userId);
    }

    /** 拒绝：status=REJECTED，从待审核区间移除。 */
    public void reject(long userId) throws IOException {
        User u = getUserByUserId(userId);
        if (u == null) return;
        u.setStatus("REJECTED");
        neuroDb.put(userId, gson.toJson(u));
        removePending(userId);
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
            json = neuroDb.get((long) legacyKey);
        } catch (IOException e) {
            log.warn("NeuroDB get user failed: {}", e.getMessage());
            return null;
        }
        if (json != null && !json.isBlank()) {
            User user = gson.fromJson(json, User.class);
            if (user != null && username.equals(user.getId()) && BCrypt.checkpw(password, user.getPasswordHash())) {
                if ("REJECTED".equals(user.getStatus())) return null;
                return jwtUtil.createToken(user.getId(), user.getRole() != null ? user.getRole() : "USER");
            }
        }
        // 新体系：username -> userId -> User
        long nameKey = usernameToKey(username);
        try {
            String idStr = neuroDb.get(nameKey);
            if (idStr == null || idStr.isBlank()) return null;
            long userId = Long.parseLong(idStr.trim());
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
        return switch (userId.toUpperCase()) {
            case "A" -> (int) userKeyNamespace + 1;
            case "B" -> (int) userKeyNamespace + 2;
            case "C" -> (int) userKeyNamespace + 3;
            default -> (int) (userKeyNamespace + Math.abs(userId.hashCode() % 10000));
        };
    }

    public boolean userExists(String username) throws IOException {
        if (username == null) return false;
        if ("A".equalsIgnoreCase(username) || "B".equalsIgnoreCase(username) || "C".equalsIgnoreCase(username))
            return neuroDb.get((long) userIdToKey(username)) != null;
        return neuroDb.get(usernameToKey(username)) != null;
    }

    /** 仅用于兼容旧版初始化 A/B/C。 */
    public void initUser(String id, String plainPassword) throws IOException {
        String hash = BCrypt.hashpw(plainPassword, BCrypt.gensalt(10));
        User user = new User(id, hash);
        int key = userIdToKey(id);
        neuroDb.put((long) key, gson.toJson(user));
    }

    /** 初始化管理员账号（username=admin, 需 APPROVED）。 */
    public void initAdminIfAbsent(String adminUsername, String password) throws IOException {
        long nameKey = usernameToKey(adminUsername);
        if (neuroDb.get(nameKey) != null) return;
        long userId = nextUserId();
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(10));
        User admin = new User(userId, adminUsername, hash, "ADMIN", "APPROVED");
        neuroDb.put(userId, gson.toJson(admin));
        neuroDb.put(nameKey, String.valueOf(userId));
        log.info("Initialized admin user {}", adminUsername);
    }

    /** Update user profile (nickname, avatarUrl). */
    public void updateProfile(String username, String nickname, String avatarUrl) throws IOException {
        User u = getUserByUsernameOrId(username);
        if (u == null) return;
        if (nickname != null) u.setNickname(nickname);
        if (avatarUrl != null) u.setAvatarUrl(avatarUrl);
        neuroDb.put(u.getUserId(), gson.toJson(u));
        log.info("Updated profile for user {}", username);
    }
}
