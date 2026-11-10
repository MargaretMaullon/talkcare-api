package com.talkcare.api.repository;

import com.talkcare.api.domain.activity.Notification;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class NotificationRepository {

    private final JdbcClient jdbc;

    public NotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void create(int userId, String type, String message) {
        jdbc.sql("INSERT INTO notifications (user_id, type, message) VALUES (:userId, :type, :message)")
                .param("userId", userId)
                .param("type", type)
                .param("message", message)
                .update();
    }

    /** Notifies every active administrator. */
    public void notifyAdmins(String type, String message) {
        jdbc.sql("""
                        INSERT INTO notifications (user_id, type, message)
                        SELECT u.id, :type, :message
                        FROM users u JOIN roles r ON r.role_id = u.role_id
                        WHERE r.code = 'ADMIN' AND u.status NOT IN ('SUSPENDED', 'DEACTIVATED')
                        """)
                .param("type", type)
                .param("message", message)
                .update();
    }

    /** The 50 most recent. */
    public List<Notification> findForUser(int userId) {
        return jdbc.sql("""
                        SELECT id, type, message, is_read, created_at, read_at FROM notifications
                        WHERE user_id = :userId ORDER BY created_at DESC LIMIT 50
                        """)
                .param("userId", userId)
                .query((rs, n) -> new Notification(
                        rs.getInt("id"),
                        rs.getString("type"),
                        rs.getString("message"),
                        rs.getBoolean("is_read"),
                        Rows.instant(rs, "created_at"),
                        Rows.instant(rs, "read_at")))
                .list();
    }

    public void markAllRead(int userId) {
        jdbc.sql("UPDATE notifications SET is_read = TRUE, read_at = now() WHERE user_id = :userId AND is_read = FALSE")
                .param("userId", userId)
                .update();
    }
}
