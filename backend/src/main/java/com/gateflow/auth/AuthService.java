package com.gateflow.auth;

import com.gateflow.http.ApiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {
    private final UserRepository users;
    private final SessionService sessions;
    private final PasswordEncoder encoder;
    private final String dummyHash;
    private final AuthRateLimiter limiter;
    private final TransactionTemplate transactions;

    public AuthService(UserRepository users, SessionService sessions, PasswordEncoder encoder,
            AuthRateLimiter limiter, TransactionTemplate transactions) {
        this.users = users; this.sessions = sessions; this.encoder = encoder;
        this.limiter = limiter; this.transactions = transactions;
        this.dummyHash = encoder.encode(UUID.randomUUID().toString());
    }

    public Result signup(AuthRequests.Signup request, String previousToken) {
        UserAccount user = new UserAccount(normalizeEmail(request.email()), request.displayName().trim(),
                encoder.encode(request.password()));
        // BCrypt runs before the short atomic user/session write transaction.
        return transactions.execute(transaction -> {
            try {
                users.saveAndFlush(user);
            } catch (DataIntegrityViolationException conflict) {
                throw new ApiException(HttpStatus.CONFLICT, "ACCOUNT_CONFLICT", "Unable to create an account with these details");
            }
            return new Result(user.principal(), sessions.create(user.getId(), previousToken));
        });
    }

    public Result login(AuthRequests.Login request, String previousToken) {
        String email = normalizeEmail(request.email());
        // This write commits independently of credential failure; never roll back abuse counters.
        if (!limiter.allowLoginAccount(email)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many authentication attempts", limiter.retryAfterSeconds());
        }
        UserAccount user = users.findByEmailIgnoreCase(email).orElse(null);
        // Always execute a hash check, including unknown accounts; not a timing-proof claim.
        boolean matches = encoder.matches(request.password(), user == null ? dummyHash : user.getPasswordHash());
        if (!matches || user == null || !user.isActive()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid email or password");
        }
        return new Result(user.principal(), sessions.create(user.getId(), previousToken));
    }

    private static String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }
    public record Result(UserPrincipal user, String token) {
        @Override public String toString() { return "AuthResult[REDACTED]"; }
    }
}
