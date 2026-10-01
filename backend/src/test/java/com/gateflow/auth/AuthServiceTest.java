package com.gateflow.auth;

import com.gateflow.http.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.util.Optional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final SessionService sessions = mock(SessionService.class);
    private final PasswordEncoder encoder = mock(PasswordEncoder.class);
    private final AuthRateLimiter limiter = mock(AuthRateLimiter.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private AuthService service;
    @BeforeEach void setup() {
        when(encoder.encode(anyString())).thenReturn("encoded-password-hash");
        when(limiter.allowLoginAccount(anyString())).thenReturn(true);
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
        service = new AuthService(users, sessions, encoder, limiter, transactions);
    }
    @Test void signupNormalizesIdentityAndHashesPassword() {
        when(sessions.create(any(), isNull())).thenReturn("opaque-test-token");
        var result = service.signup(new AuthRequests.Signup("USER@EXAMPLE.TEST", " User ", "strong-password-2026"), null);
        var capture = ArgumentCaptor.forClass(UserAccount.class);
        verify(users).saveAndFlush(capture.capture());
        assertThat(capture.getValue().getEmail()).isEqualTo("user@example.test");
        assertThat(capture.getValue().getDisplayName()).isEqualTo("User");
        assertThat(capture.getValue().getPasswordHash()).isEqualTo("encoded-password-hash");
        verify(encoder).encode("strong-password-2026");
        assertThat(result.toString()).doesNotContain("opaque-test-token");
    }
    @Test void duplicateSignupReturnsConflictWithoutCreatingSession() {
        when(users.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("simulated constraint"));
        assertThatThrownBy(() -> service.signup(new AuthRequests.Signup("a@example.test", "A", "strong-password-2026"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status().value()).isEqualTo(409));
        verifyNoInteractions(sessions);
    }
    @Test void unknownAccountStillExecutesPasswordHashCheck() {
        when(users.findByEmailIgnoreCase("missing@example.test")).thenReturn(Optional.empty());
        when(encoder.matches(anyString(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.login(new AuthRequests.Login("missing@example.test", "wrong-password"), null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("INVALID_CREDENTIALS"));
        verify(encoder).matches("wrong-password", "encoded-password-hash");
        verifyNoInteractions(sessions);
    }
    @Test void successfulLoginRotatesPresentedSession() {
        var user = new UserAccount("a@example.test", "A", "stored-password-hash");
        when(users.findByEmailIgnoreCase("a@example.test")).thenReturn(Optional.of(user));
        when(encoder.matches("correct-password", "stored-password-hash")).thenReturn(true);
        when(sessions.create(user.getId(), "previous-token")).thenReturn("fresh-token");
        assertThat(service.login(new AuthRequests.Login("A@EXAMPLE.TEST", "correct-password"), "previous-token").token())
                .isEqualTo("fresh-token");
        verify(sessions).create(user.getId(), "previous-token");
    }
}
