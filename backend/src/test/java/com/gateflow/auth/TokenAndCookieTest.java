package com.gateflow.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.HashSet;
import static org.assertj.core.api.Assertions.*;

class TokenAndCookieTest {
    private final TokenCodec codec = new TokenCodec();
    @Test void generatedTokensHaveExpectedFormatAndAreDistinctInSample() {
        var tokens = new HashSet<String>();
        for (int i = 0; i < 1000; i++) {
            String token = codec.generate();
            assertThat(token).hasSize(43);
            assertThat(codec.isSessionToken(token)).isTrue();
            tokens.add(token);
        }
        assertThat(tokens).hasSize(1000); // Not a proof of entropy; SecureRandom supplies it.
    }
    @Test void tokensAreHashedAndMalformedInputsRejected() {
        String token = codec.generate();
        assertThat(codec.hash(token)).hasSize(64).isNotEqualTo(token);
        assertThat(codec.hash(token)).isEqualTo(codec.hash(token));
        assertThat(codec.isSessionToken(null)).isFalse();
        assertThat(codec.isSessionToken("short")).isFalse();
    }
    @Test void sessionCookieIsSecureHttpOnlyHostOnlyAndLax() {
        var cookies = new AuthCookies(new AuthProperties(Duration.ofHours(12), true, 10, 5, Duration.ofMinutes(1)));
        var response = new MockHttpServletResponse();
        cookies.set(response, codec.generate());
        assertThat(response.getHeader("Set-Cookie")).startsWith("__Host-GATEFLOW_SESSION=").contains("Secure", "HttpOnly", "SameSite=Lax", "Path=/", "Max-Age=43200")
                .doesNotContain("Domain=");
        cookies.clear(response);
        assertThat(response.getHeaders("Set-Cookie").getLast()).contains("Max-Age=0");
    }
    @Test void secureCsrfCookieUsesHostPrefixButRemainsReadableByJavascript() {
        var properties = new AuthProperties(Duration.ofHours(12), true, 10, 5, Duration.ofMinutes(1));
        var repository = new SecurityConfig().csrfRepository(properties);
        var request = new MockHttpServletRequest();
        request.setSecure(true);
        var response = new MockHttpServletResponse();
        repository.saveToken(repository.generateToken(request), request, response);
        assertThat(response.getHeader("Set-Cookie")).startsWith("__Host-XSRF-TOKEN=")
                .contains("Secure", "Path=/").doesNotContain("HttpOnly", "Domain=");
        assertThat(response.getCookie("__Host-XSRF-TOKEN").getAttribute("SameSite")).isEqualTo("Lax");
    }
    @Test void ambiguousDuplicateSessionCookiesAreNotAuthenticated() {
        var cookies = new AuthCookies(new AuthProperties(Duration.ofHours(12), false, 10, 5, Duration.ofMinutes(1)));
        var request = new MockHttpServletRequest();
        request.setCookies(new Cookie(AuthCookies.SESSION_NAME, codec.generate()), new Cookie(AuthCookies.SESSION_NAME, codec.generate()));
        assertThat(cookies.read(request)).isNull();
    }
}
