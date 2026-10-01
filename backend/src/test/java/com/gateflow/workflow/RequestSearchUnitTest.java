package com.gateflow.workflow;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.gateflow.http.ApiException;
import com.gateflow.rbac.*;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;

import java.time.*;
import java.util.*;

class RequestSearchUnitTest {
    RequestSearchQuery parse(String... pairs) {
        var p = new LinkedMultiValueMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) p.add(pairs[i], pairs[i + 1]);
        return RequestSearchQuery.parse(p, null);
    }

    final SearchCursor cursor =
            new SearchCursor(
                    new ObjectMapper()
                            .registerModule(new JavaTimeModule())
                            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
                    new WorkflowJson(
                            new ObjectMapper()
                                    .registerModule(new JavaTimeModule())
                                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)));

    RbacDtos.AccessView actor(UUID org, UUID member) {
        return new RbacDtos.AccessView(
                org, member, UUID.randomUUID(), List.of(), Set.of(Permission.REQUEST_VIEW_ALL));
    }

    @Test
    void defaultsAreBoundedAndDefensivelyCopied() {
        var q = parse();
        assertThat(q.limit()).isEqualTo(20);
        assertThat(q.scope()).isEqualTo(RequestSearchQuery.Scope.VISIBLE);
        assertThat(q.pagination()).isEqualTo(RequestSearchQuery.Pagination.CURSOR);
        assertThatThrownBy(() -> q.statuses().add(WorkflowDtos.RequestState.DRAFT))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void repeatedStatusesAreDeduplicated() {
        assertThat(parse("status", "DRAFT", "status", "DRAFT").statuses())
                .containsExactly(WorkflowDtos.RequestState.DRAFT);
    }

    @Test
    void offsetDateTimesNormalizeToInstant() {
        assertThat(parse("createdFrom", "2020-01-01T05:30:00+05:30").createdFrom())
                .isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
    }

    @Test
    void scalarDuplicatesAndUnknownKeysFail() {
        assertThatThrownBy(() -> parse("limit", "1", "limit", "2"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> parse("organization", "spoofed")).isInstanceOf(ApiException.class);
    }

    @Test
    void noOffsetWithCursorEvenZero() {
        assertThatThrownBy(() -> parse("offset", "0")).isInstanceOf(ApiException.class);
    }

    @Test
    void reversedAndEqualDateRangesFail() {
        for (String b : List.of("2020-01-01T00:00:00Z", "2019-01-01T00:00:00Z"))
            assertThatThrownBy(
                            () -> parse("createdFrom", "2020-01-01T00:00:00Z", "createdBefore", b))
                    .isInstanceOf(ApiException.class);
    }

    @Test
    void cursorRoundTripPreservesMicrosecondCoordinates() {
        var now = Instant.parse("2020-01-02T00:00:00.123456Z");
        var before = now.minusSeconds(1);
        var id = UUID.randomUUID();
        var token = cursor.encode(now, before, id, "fixture-context");
        var p = cursor.decode(token, "fixture-context");
        assertThat(p.asOf()).isEqualTo(now);
        assertThat(p.afterCreatedAt()).isEqualTo(before);
        assertThat(p.afterId()).isEqualTo(id);
        assertThat(token).doesNotContain("=");
    }

    @Test
    void contextBindsTenantActorScopeAndFiltersButNotLimit() {
        UUID org = UUID.randomUUID(), member = UUID.randomUUID();
        var a = actor(org, member);
        String c = cursor.context(a, parse());
        assertThat(cursor.context(a, parse("limit", "100"))).isEqualTo(c);
        assertThat(cursor.context(actor(UUID.randomUUID(), member), parse())).isNotEqualTo(c);
        assertThat(cursor.context(actor(org, UUID.randomUUID()), parse())).isNotEqualTo(c);
        assertThat(cursor.context(a, parse("scope", "OWN"))).isNotEqualTo(c);
        assertThat(cursor.context(a, parse("q", "one"))).isNotEqualTo(c);
    }

    @Test
    void statusOrderAndZoneRepresentationsProduceSameContext() {
        var a = actor(UUID.randomUUID(), UUID.randomUUID());
        assertThat(
                        cursor.context(
                                a,
                                parse(
                                        "status",
                                        "DRAFT",
                                        "status",
                                        "APPROVED",
                                        "createdFrom",
                                        "2020-01-01T00:00:00Z")))
                .isEqualTo(
                        cursor.context(
                                a,
                                parse(
                                        "status",
                                        "APPROVED",
                                        "status",
                                        "DRAFT",
                                        "createdFrom",
                                        "2020-01-01T05:30:00+05:30")));
    }

    @Test
    void cursorRejectsWrongContextAndFutureAnchor() {
        var t = Instant.parse("2020-01-01T00:00:00Z");
        assertThatThrownBy(() -> cursor.decode(cursor.encode(t, t, UUID.randomUUID(), "a"), "b"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () ->
                                cursor.decode(
                                        cursor.encode(t, t.plusSeconds(1), UUID.randomUUID(), "a"),
                                        "a"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void cursorRejectsExtraFieldsNonIntegralVersionTrailingJsonAndNanoseconds() throws Exception {
        var t = Instant.parse("2020-01-01T00:00:00Z");
        String valid = cursor.encode(t, t, UUID.randomUUID(), "a");
        String json =
                new String(
                        Base64.getUrlDecoder().decode(valid),
                        java.nio.charset.StandardCharsets.UTF_8);
        for (String bad :
                List.of(
                        json.replace("\"version\":1", "\"version\":1.0"),
                        json.substring(0, json.length() - 1) + ",\"extra\":true}",
                        json + " {}"))
            assertThatThrownBy(
                            () ->
                                    cursor.decode(
                                            Base64.getUrlEncoder()
                                                    .withoutPadding()
                                                    .encodeToString(
                                                            bad.getBytes(
                                                                    java.nio.charset
                                                                            .StandardCharsets
                                                                            .UTF_8)),
                                            "a"))
                    .isInstanceOf(ApiException.class);
        assertThatThrownBy(
                        () ->
                                cursor.decode(
                                        cursor.encode(t.plusNanos(1), t, UUID.randomUUID(), "a"),
                                        "a"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void sortsAndTermsAreNotInterpolatedIntoSql() {
        var jdbc = org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
        var repo = new RequestSearchRepository(jdbc, new RequestAccessPolicy(jdbc));
        var statement =
                repo.statement(
                        actor(UUID.randomUUID(), UUID.randomUUID()),
                        parse("q", "' OR 1=1 --", "sort", "CREATED_ASC"),
                        null);
        assertThat(statement.sql())
                .contains("ORDER BY r.created_at ASC,r.id ASC", "LIMIT :fetch", ":q")
                .doesNotContain("' OR 1=1 --");
        assertThat(statement.params().getValue("fetch")).isEqualTo(21);
    }

    @Test
    void inboxUsesActiveJoinAndRoleEligibilityBeforeLimit() {
        var jdbc = org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
        var repo = new RequestSearchRepository(jdbc, new RequestAccessPolicy(jdbc));
        var statement =
                repo.statement(
                        actor(UUID.randomUUID(), UUID.randomUUID()), parse("scope", "INBOX"), null);
        assertThat(statement.sql())
                .contains(
                        "FROM requests r JOIN request_steps",
                        "s.assigned_membership_id=:actor",
                        "mr.role_id=w.approver_role_id")
                .doesNotContain("LEFT JOIN");
    }
}
