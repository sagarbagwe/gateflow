package com.gateflow.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.gateflow.http.ApiException;
import org.springframework.http.HttpStatus;
import java.util.Optional;
import java.util.UUID;

@Service
public class SessionService {
    private final JdbcTemplate jdbc;
    private final TokenCodec codec;
    private final AuthProperties properties;
    public SessionService(JdbcTemplate jdbc, TokenCodec codec, AuthProperties properties) {
        this.jdbc = jdbc; this.codec = codec; this.properties = properties;
    }

    // Joins signup transaction; owns a short rotation transaction for login.
    @Transactional
    public String create(UUID userId, String previousToken) {
        String status = jdbc.queryForObject("SELECT status FROM users WHERE id = ? FOR SHARE", String.class, userId);
        if (!"ACTIVE".equals(status)) throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        revoke(previousToken);
        String raw = codec.generate();
        jdbc.update("""
                INSERT INTO auth_sessions (user_id, token_hash, expires_at)
                VALUES (?, ?, CURRENT_TIMESTAMP + (? * INTERVAL '1 second'))
                """, userId, codec.hash(raw), properties.sessionTtl().toSeconds());
        return raw;
    }

    public Optional<UserPrincipal> authenticate(String raw) {
        if (!codec.isSessionToken(raw)) return Optional.empty();
        return jdbc.query("""
                SELECT u.id, u.email, u.display_name
                FROM auth_sessions s JOIN users u ON u.id = s.user_id
                WHERE s.token_hash = ? AND s.revoked_at IS NULL
                  AND s.expires_at > CURRENT_TIMESTAMP AND u.status = 'ACTIVE'
                """, (rs, row) -> new UserPrincipal(rs.getObject("id", UUID.class),
                    rs.getString("email"), rs.getString("display_name")), codec.hash(raw))
                .stream().findFirst();
    }

    public void revoke(String raw) {
        if (codec.isSessionToken(raw)) {
            jdbc.update("UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, CURRENT_TIMESTAMP) WHERE token_hash = ?",
                    codec.hash(raw));
        }
    }
}
