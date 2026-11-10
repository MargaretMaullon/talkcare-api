package com.talkcare.api.repository;

import com.talkcare.api.domain.verification.PendingVerification;
import com.talkcare.api.domain.verification.VerificationStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code identity_verifications}; at most one PENDING row per user (partial unique index). */
@Repository
public class VerificationRepository {

    private final JdbcClient jdbc;

    public VerificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Submission(int userId, int idTypeId, String idNumber, String documentUrl) {
    }

    /** {@code previousDocumentUrl} is the replaced pending document, if any. */
    public record Upserted(int id, String previousDocumentUrl) {
    }

    public record LockedVerification(int id, int userId, String fullName) {
    }

    /** Inserts a PENDING verification, or replaces the user's open one. Call inside a transaction. */
    public Upserted upsertPending(Submission submission) {
        Optional<Upserted> existing = jdbc.sql("""
                        SELECT id, document_url FROM identity_verifications
                        WHERE user_id = :userId AND status = 'PENDING' FOR UPDATE
                        """)
                .param("userId", submission.userId())
                .query((rs, n) -> new Upserted(rs.getInt("id"), rs.getString("document_url")))
                .optional();
        if (existing.isPresent()) {
            jdbc.sql("""
                            UPDATE identity_verifications
                            SET id_type_id = :idTypeId, id_number = :idNumber, document_url = :documentUrl, submitted_at = now()
                            WHERE id = :id
                            """)
                    .param("id", existing.get().id())
                    .param("idTypeId", submission.idTypeId())
                    .param("idNumber", submission.idNumber())
                    .param("documentUrl", submission.documentUrl())
                    .update();
            return existing.get();
        }
        int id = jdbc.sql("""
                        INSERT INTO identity_verifications (user_id, id_type_id, id_number, document_url)
                        VALUES (:userId, :idTypeId, :idNumber, :documentUrl) RETURNING id
                        """)
                .param("userId", submission.userId())
                .param("idTypeId", submission.idTypeId())
                .param("idNumber", submission.idNumber())
                .param("documentUrl", submission.documentUrl())
                .query(Integer.class)
                .single();
        return new Upserted(id, null);
    }

    public List<PendingVerification> listPending() {
        return jdbc.sql("""
                        SELECT v.id, v.user_id, r.code AS role_code,
                               COALESCE(at.bar_roll_no, ps.prc_license_no) AS license_no,
                               up.full_name, u.email, it.name AS id_type, v.id_number,
                               up.address, up.contact_no, v.document_url, v.status, v.submitted_at
                        FROM identity_verifications v
                        JOIN users u ON u.id = v.user_id
                        JOIN roles r ON r.role_id = u.role_id
                        JOIN id_types it ON it.id_type_id = v.id_type_id
                        LEFT JOIN user_profiles up ON up.user_id = v.user_id
                        LEFT JOIN attorneys at ON at.user_id = v.user_id
                        LEFT JOIN psychiatrists ps ON ps.user_id = v.user_id
                        WHERE v.status = 'PENDING' AND u.status NOT IN ('SUSPENDED', 'DEACTIVATED')
                        ORDER BY v.submitted_at DESC
                        """)
                .query((rs, n) -> new PendingVerification(
                        rs.getInt("id"),
                        rs.getInt("user_id"),
                        rs.getString("role_code"),
                        rs.getString("license_no"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("id_type"),
                        rs.getString("id_number"),
                        rs.getString("address"),
                        rs.getString("contact_no"),
                        rs.getString("document_url"),
                        VerificationStatus.valueOf(rs.getString("status")),
                        Rows.instant(rs, "submitted_at")))
                .list();
    }

    public Optional<LockedVerification> lockPending(int id) {
        return jdbc.sql("""
                        SELECT v.id, v.user_id, up.full_name
                        FROM identity_verifications v LEFT JOIN user_profiles up ON up.user_id = v.user_id
                        WHERE v.id = :id AND v.status = 'PENDING'
                        FOR UPDATE OF v
                        """)
                .param("id", id)
                .query((rs, n) -> new LockedVerification(rs.getInt("id"), rs.getInt("user_id"), rs.getString("full_name")))
                .optional();
    }

    public void review(int id, VerificationStatus status, int reviewerId, String rejectionReason) {
        jdbc.sql("""
                        UPDATE identity_verifications
                        SET status = :status, reviewed_by = :reviewerId, reviewed_at = now(), rejection_reason = :reason
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("status", status.name())
                .param("reviewerId", reviewerId)
                .param("reason", rejectionReason)
                .update();
    }

    /**
     * Opens a new PENDING verification copied from the user's latest one, unless one is already
     * pending. Returns false when the user never submitted an ID. Call inside a transaction.
     */
    public boolean requeueLatest(int userId) {
        Integer pending = jdbc.sql("SELECT COUNT(*)::int FROM identity_verifications WHERE user_id = :userId AND status = 'PENDING'")
                .param("userId", userId)
                .query(Integer.class)
                .single();
        if (pending > 0) {
            return true;
        }
        return jdbc.sql("""
                        INSERT INTO identity_verifications (user_id, id_type_id, id_number, document_url)
                        SELECT user_id, id_type_id, id_number, document_url FROM identity_verifications
                        WHERE user_id = :userId ORDER BY submitted_at DESC, id DESC LIMIT 1
                        """)
                .param("userId", userId)
                .update() > 0;
    }

    public int countPending() {
        return jdbc.sql("""
                        SELECT COUNT(*)::int FROM identity_verifications v JOIN users u ON u.id = v.user_id
                        WHERE v.status = 'PENDING' AND u.status NOT IN ('SUSPENDED', 'DEACTIVATED')
                        """)
                .query(Integer.class)
                .single();
    }
}
