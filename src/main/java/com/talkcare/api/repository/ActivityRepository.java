package com.talkcare.api.repository;

import com.talkcare.api.domain.activity.ActivityEntry;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The admin audit trail ({@code activity_log}). Every logged event is also delivered as an
 * in-app notification to each active administrator other than the one who caused it, except
 * report events: concerns go to the receiving attorney/psychiatrist, not to admins.
 */
@Repository
public class ActivityRepository {

    private final JdbcClient jdbc;

    public ActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record NewActivity(Integer actorId, String entityType, int entityId, String action, String details) {
    }

    public void log(NewActivity activity) {
        jdbc.sql("""
                        INSERT INTO activity_log (actor_user_id, entity_type, entity_id, action, details)
                        VALUES (:actorId, :entityType, :entityId, :action, :details)
                        """)
                .param("actorId", activity.actorId())
                .param("entityType", activity.entityType())
                .param("entityId", activity.entityId())
                .param("action", activity.action())
                .param("details", activity.details())
                .update();
        if ("REPORT".equals(activity.entityType())) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO notifications (user_id, type, message)
                        SELECT u.id, 'ACTIVITY', :details
                        FROM users u JOIN roles r ON r.role_id = u.role_id
                        WHERE r.code = 'ADMIN' AND u.status NOT IN ('SUSPENDED', 'DEACTIVATED')
                          AND u.id IS DISTINCT FROM :actorId
                        """)
                .param("actorId", activity.actorId())
                .param("details", activity.details())
                .update();
    }

    public List<ActivityEntry> recent(int limit) {
        return jdbc.sql("""
                        SELECT a.action, a.details, a.entity_type, a.entity_id, a.created_at, up.full_name AS actor_name
                        FROM activity_log a LEFT JOIN user_profiles up ON up.user_id = a.actor_user_id
                        ORDER BY a.created_at DESC, a.id DESC LIMIT :limit
                        """)
                .param("limit", limit)
                .query((rs, n) -> new ActivityEntry(
                        rs.getString("action"),
                        rs.getString("details"),
                        rs.getString("entity_type"),
                        rs.getInt("entity_id"),
                        rs.getString("actor_name"),
                        Rows.instant(rs, "created_at")))
                .list();
    }
}
