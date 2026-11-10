package com.talkcare.api.repository;

import com.talkcare.api.domain.account.PendingPasswordReset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Forgot-password requests; the new password is stored as a hash until an admin approves it. */
@Repository
public class PasswordResetRepository {

    private final JdbcClient jdbc;

    public PasswordResetRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Locked(int userId, String passwordHash) {
    }

    /** Replaces the user's open request, if any. Call inside a transaction. */
    public void replacePending(int userId, String passwordHash) {
        jdbc.sql("DELETE FROM password_reset_requests WHERE user_id = :userId AND status = 'PENDING'")
                .param("userId", userId).update();
        jdbc.sql("INSERT INTO password_reset_requests (user_id, password_hash) VALUES (:userId, :hash)")
                .param("userId", userId)
                .param("hash", passwordHash)
                .update();
    }

    public List<PendingPasswordReset> listPending() {
        return jdbc.sql("""
                        SELECT p.id, p.user_id, r.code AS role_code, up.full_name, u.username, u.email, p.requested_at
                        FROM password_reset_requests p
                        JOIN users u ON u.id = p.user_id
                        JOIN roles r ON r.role_id = u.role_id
                        LEFT JOIN user_profiles up ON up.user_id = p.user_id
                        WHERE p.status = 'PENDING'
                        ORDER BY p.requested_at DESC
                        """)
                .query((rs, n) -> new PendingPasswordReset(rs.getInt("id"), rs.getInt("user_id"),
                        rs.getString("role_code"), rs.getString("full_name"), rs.getString("username"),
                        rs.getString("email"), rs.getTimestamp("requested_at").toInstant()))
                .list();
    }

    public int countPending() {
        return jdbc.sql("SELECT COUNT(*) FROM password_reset_requests WHERE status = 'PENDING'")
                .query(Integer.class).single();
    }

    /** An open request, locked for update. */
    public Optional<Locked> lockPending(int id) {
        return jdbc.sql("SELECT user_id, password_hash FROM password_reset_requests WHERE id = :id AND status = 'PENDING' FOR UPDATE")
                .param("id", id)
                .query((rs, n) -> new Locked(rs.getInt("user_id"), rs.getString("password_hash")))
                .optional();
    }

    /** Closes a request; the stored hash is cleared so it never outlives the decision. */
    public void close(int id, boolean approved, int reviewerId, String reason) {
        jdbc.sql("""
                        UPDATE password_reset_requests
                        SET status = :status, reason = :reason, reviewed_by = :reviewer, reviewed_at = now(),
                            password_hash = ''
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("status", approved ? "APPROVED" : "REJECTED")
                .param("reason", reason)
                .param("reviewer", reviewerId)
                .update();
    }
}
