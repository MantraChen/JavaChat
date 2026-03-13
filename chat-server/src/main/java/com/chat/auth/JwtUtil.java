package com.chat.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

/**
 * JWT 签发与校验。密钥使用配置的 jwtSecret，过期时间可配置。
 */
public class JwtUtil {
    private static final Logger log = LoggerFactory.getLogger(JwtUtil.class);

    private final SecretKey key;
    private final long expirationMs;

    public JwtUtil(String jwtSecret, long expirationMs) {
        this.key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        this.expirationMs = expirationMs;
    }

    private static final String CLAIM_ROLE = "role";

    /** 签发 JWT，sub=用户名/登录名，claim role=USER|ADMIN。 */
    public String createToken(String subject, String role) {
        return Jwts.builder()
                .subject(subject)
                .claim(CLAIM_ROLE, role != null ? role : "USER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    /** 兼容旧版：无 role 时视为 USER。 */
    public String createToken(String subject) {
        return createToken(subject, "USER");
    }

    /** 校验并解析 JWT，返回 sub（用户名）；无效或过期返回 null。 */
    public String parseUserId(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            return Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
        } catch (JwtException e) {
            log.debug("JWT parse failed: {}", e.getMessage());
            return null;
        }
    }

    /** 解析 JWT 中的 role（ADMIN / USER），无效返回 null。 */
    public String parseRole(String token) {
        if (token == null || token.isBlank()) return null;
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String role = (String) claims.get(CLAIM_ROLE);
            return role != null ? role : "USER";
        } catch (JwtException e) {
            return null;
        }
    }
}
