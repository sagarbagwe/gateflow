package com.gateflow.notifications;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class PreferenceRepository {
    public record Preferences(boolean inAppEnabled, boolean emailEnabled, long version) {}

    private final JdbcTemplate jdbc;

    public PreferenceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Preferences find(UUID org, UUID member) {
        return jdbc
                .query(
                        "SELECT in_app_enabled,email_enabled,row_version FROM"
                                + " notification_preferences WHERE organization_id=? AND"
                                + " membership_id=?",
                        (rs, n) ->
                                new Preferences(rs.getBoolean(1), rs.getBoolean(2), rs.getLong(3)),
                        org,
                        member)
                .stream()
                .findFirst()
                .orElse(new Preferences(true, false, 0));
    }

    public boolean update(UUID org, UUID member, boolean inApp, boolean email, long version) {
        jdbc.update(
                "INSERT INTO notification_preferences(organization_id,membership_id) VALUES(?,?) ON"
                        + " CONFLICT DO NOTHING",
                org,
                member);
        return jdbc.update(
                        "UPDATE notification_preferences SET"
                            + " in_app_enabled=?,email_enabled=?,row_version=row_version+1,updated_at=clock_timestamp()"
                            + " WHERE organization_id=? AND membership_id=? AND row_version=?",
                        inApp,
                        email,
                        org,
                        member,
                        version)
                == 1;
    }
}
