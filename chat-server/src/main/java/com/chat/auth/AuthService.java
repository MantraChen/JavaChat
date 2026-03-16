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
 * NeuroDB Key：待审核 PENDING_NS 区间；已批准通讯录 APPROVED_MAILBOX_NS 信箱；用户名哈希->userId；userId->UserData JSON。
 */
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    /** 待审核用户 key 区间 [PENDING_NS, PENDING_NS + PENDING_RANGE)，单 key  per userId，value 非 REMOVED 即待审核。 */
    private static final long PENDING_NS = 200_000_000L;
    private static final long PENDING_RANGE = 100_000_000L;
    /** 已批准用户通讯录信箱（OwnerId=1）：[APPROVED_MAILBOX_NS, APPROVED_MAILBOX_NS + RANGE)，仅拉此区间即得通讯录，避免全表扫描。 */
    private static final long APPROVED_MAILBOX_NS = 300_000_000L;
    private static final long APPROVED_MAILBOX_RANGE = 100_000_000L;
    private static final String PENDING_TOMBSTONE = "REMOVED";
    private static final int USERNAME_MAP_NS = 100_000_000;
    private static final int USER_ID_START = 2_000_000;

    private final NeuroDbClient neuroDb;
    private final JwtUtil jwtUtil;
    private final Gson gson = new Gson();
    private final SnowflakeId snowflakeId = new SnowflakeId();

    public AuthService(NeuroDbClient neuroDb, AppConfig config, JwtUtil jwtUtil) {
        this.neuroDb = neuroDb;
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

    /** 按 username 或数字 userId 取用户（JWT subject 为 username）。 */
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

    /** 精准拉取「已批准用户通讯录」信箱，仅 scan 该区间，避免全表扫描。 */
    public List<User> getAllApprovedUsers() throws IOException {
        List<User> out = new ArrayList<>();
        for (NeuroDbClient.ScanRecord rec : neuroDb.scan(APPROVED_MAILBOX_NS, APPROVED_MAILBOX_NS + APPROVED_MAILBOX_RANGE)) {
            if (rec.value == null || PENDING_TOMBSTONE.equals(rec.value)) continue;
            long userId = rec.key - APPROVED_MAILBOX_NS;
            User u = getUserByUserId(userId);
            if (u != null && "APPROVED".equals(u.getStatus())) out.add(u);
        }
        return out;
    }

    private void addToApprovedMailbox(long userId) throws IOException {
        neuroDb.put(APPROVED_MAILBOX_NS + userId, "1");
    }

    private void removeFromApprovedMailbox(long userId) throws IOException {
        neuroDb.put(APPROVED_MAILBOX_NS + userId, PENDING_TOMBSTONE);
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

    /** 带时效的状态修改：until=0 表示永久。同时维护已批准通讯录信箱（APPROVED 入信箱，MUTED/BANNED/REJECTED 移出）。 */
    public void changeUserStatusWithDuration(long userId, String newStatus, long until) throws IOException {
        User u = getUserByUserId(userId);
        if (u == null) return;
        u.setStatus(newStatus);
        if ("MUTED".equals(newStatus)) {
            u.setMuteUntil(until > 0 ? until : null);
            u.setBanUntil(null);
            removeFromApprovedMailbox(userId);
        } else if ("BANNED".equals(newStatus)) {
            u.setBanUntil(until > 0 ? until : null);
            u.setMuteUntil(null);
            u.setTokenVersion(u.getTokenVersion() + 1);
            removeFromApprovedMailbox(userId);
        } else if ("REJECTED".equals(newStatus)) {
            u.setMuteUntil(null);
            u.setBanUntil(null);
            removeFromApprovedMailbox(userId);
        } else {
            u.setMuteUntil(null);
            u.setBanUntil(null);
            if ("APPROVED".equals(newStatus)) addToApprovedMailbox(userId);
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

    /** 审批通过：复用 changeUserStatus 更新状态并维护通讯录信箱，再从待审核区间移除。 */
    public void approve(long userId) throws IOException {
        changeUserStatus(userId, "APPROVED");
        removePending(userId);
        log.info("Approved userId={}", userId);
    }

    /** 拒绝：复用 changeUserStatus 更新状态，再从待审核区间移除。 */
    public void reject(long userId) throws IOException {
        changeUserStatus(userId, "REJECTED");
        removePending(userId);
        log.info("Rejected userId={}", userId);
    }

    /** 登录：username -> userId -> User，需 APPROVED 才能登录。返回 JWT；失败返回 null。 */
    public String login(String username, String password) {
        if (username == null || password == null) return null;
        username = username.trim();
        long nameKey = usernameToKey(username);
        try {
            String idStr = neuroDb.get(nameKey);
            if (idStr == null || idStr.isBlank()) return null;
            long userId = Long.parseLong(idStr.trim());
            User user = getUserByUserId(userId);
            if (user == null || !username.equals(user.getUsername())) return null;
            if (!"APPROVED".equals(user.getStatus())) return null;
            if (!BCrypt.checkpw(password, user.getPasswordHash())) return null;
            return jwtUtil.createToken(user.getUsername(), user.getRole() != null ? user.getRole() : "USER", user.getTokenVersion());
        } catch (IOException e) {
            log.warn("Login lookup failed: {}", e.getMessage());
            return null;
        }
    }

    public boolean userExists(String username) throws IOException {
        if (username == null) return false;
        return neuroDb.get(usernameToKey(username)) != null;
    }

    /** 校验 JWT 并返回用户名：解析有效、用户存在且 tokenVersion 与 DB 一致时返回 username，否则 null。 */
    public String validateTokenAndGetUsername(String token) {
        if (token == null || token.isBlank()) return null;
        String username = jwtUtil.parseUserId(token);
        if (username == null) return null;
        try {
            User u = getUserByUsernameOrId(username);
            if (u == null || u.getTokenVersion() != jwtUtil.parseTokenVersion(token)) return null;
            return username;
        } catch (IOException e) {
            log.debug("validateToken getUser failed: {}", e.getMessage());
            return null;
        }
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

    /** 修改密码：校验旧密码后更新哈希并自增 tokenVersion，使旧 JWT 失效。成功返回 null，失败返回错误信息。 */
    public String changePassword(String username, String oldPassword, String newPassword) throws IOException {
        if (username == null || (username = username.trim()).isEmpty() || newPassword == null || newPassword.isEmpty())
            return "参数无效";
        User u = getUserByUsernameOrId(username);
        if (u == null) return "用户不存在";
        if (oldPassword == null || !BCrypt.checkpw(oldPassword, u.getPasswordHash())) return "原密码错误";
        u.setPasswordHash(BCrypt.hashpw(newPassword, BCrypt.gensalt(10)));
        u.setTokenVersion(u.getTokenVersion() + 1);
        neuroDb.put(u.getUserId(), gson.toJson(u));
        log.info("Password changed for user {}", username);
        return null;
    }
}
