package com.talkcare.api.repository;

import com.talkcare.api.domain.ServiceType;
import com.talkcare.api.domain.report.Report;
import com.talkcare.api.domain.report.ReportStatus;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class ReportRepository {

    private static final String REPORT_SELECT = """
            SELECT r.id, r.report_code, r.client_id, r.status, r.involved_witness, r.concern_details,
                   r.remarks, r.assigned_professional_id, r.created_at, r.updated_at,
                   st.code AS service_type, ct.name AS concern_type,
                   COALESCE(s.full_name, up.full_name)   AS full_name,
                   COALESCE(s.email, u.email)            AS email,
                   COALESCE(s.contact_no, up.contact_no) AS contact_no,
                   COALESCE(s.address, up.address)       AS address,
                   pup.full_name AS professional_name
            FROM reports r
            JOIN service_types st      ON st.service_type_id = r.service_type_id
            JOIN concern_types ct      ON ct.concern_type_id = r.concern_type_id
            JOIN users u               ON u.id = r.client_id
            LEFT JOIN user_profiles up ON up.user_id = r.client_id
            LEFT JOIN report_snapshots s ON s.report_id = r.id
            LEFT JOIN user_profiles pup  ON pup.user_id = r.assigned_professional_id
            """;

    private static final RowMapper<Report> MAPPER = (rs, n) -> new Report(
            rs.getInt("id"),
            rs.getString("report_code"),
            rs.getInt("client_id"),
            ServiceType.valueOf(rs.getString("service_type")),
            rs.getString("concern_type"),
            ReportStatus.valueOf(rs.getString("status")),
            Rows.text(rs, "involved_witness"),
            rs.getString("concern_details"),
            Rows.text(rs, "remarks"),
            Rows.integer(rs, "assigned_professional_id"),
            Rows.text(rs, "professional_name"),
            new Report.Reporter(
                    rs.getString("full_name"),
                    rs.getString("email"),
                    Rows.text(rs, "contact_no"),
                    Rows.text(rs, "address")),
            Rows.instant(rs, "created_at"),
            Rows.instant(rs, "updated_at"));

    private final JdbcClient jdbc;

    public ReportRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Which reports a listing may include. */
    public sealed interface Scope {
        record All() implements Scope {
        }

        record OwnedBy(int clientId) implements Scope {
        }

        record AssignedTo(int professionalId) implements Scope {
        }
    }

    public record NewReport(
            int clientId,
            int serviceTypeId,
            int concernTypeId,
            String involvedWitness,
            String concernDetails,
            Integer assignedProfessionalId,
            Report.Reporter reporter) {
    }

    /** Row locked for update while its status changes. */
    public record LockedReport(int id, int clientId, String reportCode, ReportStatus status,
                               Integer assignedProfessionalId) {
    }

    /**
     * Inserts a report with a code derived from its id (RPT-0001) plus the immutable reporter
     * snapshot. Call inside a transaction.
     */
    public int create(NewReport report) {
        int id = jdbc.sql("""
                        WITH next AS (SELECT nextval(pg_get_serial_sequence('reports', 'id')) AS id)
                        INSERT INTO reports (id, report_code, client_id, service_type_id, concern_type_id,
                                             involved_witness, concern_details, assigned_professional_id)
                        SELECT id, 'RPT-' || LPAD(id::text, GREATEST(4, LENGTH(id::text)), '0'),
                               :clientId, :serviceTypeId, :concernTypeId, :witness, :details, :professionalId
                        FROM next
                        RETURNING id
                        """)
                .param("clientId", report.clientId())
                .param("serviceTypeId", report.serviceTypeId())
                .param("concernTypeId", report.concernTypeId())
                .param("witness", report.involvedWitness())
                .param("details", report.concernDetails())
                .param("professionalId", report.assignedProfessionalId())
                .query(Integer.class)
                .single();
        Report.Reporter reporter = report.reporter();
        jdbc.sql("""
                        INSERT INTO report_snapshots (report_id, full_name, email, contact_no, address)
                        VALUES (:id, :fullName, :email, :contactNo, :address)
                        """)
                .param("id", id)
                .param("fullName", reporter.fullName())
                .param("email", reporter.email())
                .param("contactNo", reporter.contactNo())
                .param("address", reporter.address())
                .update();
        return id;
    }

    public Optional<Report> findById(int id) {
        return jdbc.sql(REPORT_SELECT + " WHERE r.id = :id").param("id", id).query(MAPPER).optional();
    }

    /** Newest first; {@code status} null means every status. */
    public List<Report> list(Scope scope, ReportStatus status) {
        List<String> where = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        switch (scope) {
            case Scope.All all -> {
                // no restriction
            }
            case Scope.OwnedBy owned -> {
                where.add("r.client_id = :clientId");
                params.put("clientId", owned.clientId());
            }
            case Scope.AssignedTo assigned -> {
                where.add("r.assigned_professional_id = :professionalId");
                params.put("professionalId", assigned.professionalId());
            }
        }
        if (status != null) {
            where.add("r.status = :status");
            params.put("status", status.name());
        }
        String clause = where.isEmpty() ? "" : " WHERE " + String.join(" AND ", where);
        return jdbc.sql(REPORT_SELECT + clause + " ORDER BY r.id DESC")
                .params(params)
                .query(MAPPER)
                .list();
    }

    public List<Report> listByStatus(ReportStatus status) {
        return jdbc.sql(REPORT_SELECT + " WHERE r.status = :status ORDER BY r.created_at DESC")
                .param("status", status.name())
                .query(MAPPER)
                .list();
    }

    /** Every report, newest first (the admin review list). */
    public List<Report> listAll() {
        return jdbc.sql(REPORT_SELECT + " ORDER BY r.created_at DESC").query(MAPPER).list();
    }

    /** Locks the report; when {@code requiredStatus} is given it must currently have that status. */
    public Optional<LockedReport> lock(int id, ReportStatus requiredStatus) {
        String sql = "SELECT id, client_id, report_code, status, assigned_professional_id FROM reports WHERE id = :id"
                + (requiredStatus == null ? "" : " AND status = :status") + " FOR UPDATE";
        var query = jdbc.sql(sql).param("id", id);
        if (requiredStatus != null) {
            query = query.param("status", requiredStatus.name());
        }
        return query.query((rs, n) -> new LockedReport(
                        rs.getInt("id"), rs.getInt("client_id"), rs.getString("report_code"),
                        ReportStatus.valueOf(rs.getString("status")),
                        Rows.integer(rs, "assigned_professional_id")))
                .optional();
    }

    public void updateStatus(int id, ReportStatus status) {
        jdbc.sql("UPDATE reports SET status = :status WHERE id = :id")
                .param("id", id)
                .param("status", status.name())
                .update();
    }

    /** Appends a line to the remarks. */
    public void appendRemarks(int id, String remarks) {
        jdbc.sql("""
                        UPDATE reports
                        SET remarks = CASE WHEN COALESCE(remarks, '') = '' THEN CAST(:remarks AS text)
                                           ELSE remarks || E'\\n' || CAST(:remarks AS text) END
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("remarks", remarks)
                .update();
    }

    public int countByStatus(ReportStatus status) {
        return jdbc.sql("SELECT COUNT(*)::int FROM reports WHERE status = :status")
                .param("status", status.name())
                .query(Integer.class)
                .single();
    }
}
