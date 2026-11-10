package com.talkcare.api.repository;

import com.talkcare.api.domain.Role;
import com.talkcare.api.domain.UserStatus;
import com.talkcare.api.domain.account.Account;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Accounts across {@code users}, {@code user_profiles} and the role subtype tables. */
@Repository
public class AccountRepository {

    private static final String ACCOUNT_SELECT = """
            SELECT u.id, u.username, u.email, u.status, u.created_at, u.last_login_at,
                   r.code AS role_code,
                   up.full_name, up.contact_no, up.address, up.birth_date, up.profile_picture_url,
                   cst.code AS client_preferred_service,
                   c.emergency_contact_name AS client_emergency_contact_name,
                   c.emergency_contact_no   AS client_emergency_contact_no,
                   p.display_title AS pro_display_title, p.bio AS pro_bio,
                   p.years_of_experience AS pro_years_of_experience,
                   p.is_accepting_clients AS pro_is_accepting_clients,
                   at.bar_roll_no AS atty_bar_roll_no, at.ibp_no AS atty_ibp_no,
                   at.law_firm AS atty_law_firm, at.jurisdiction AS atty_jurisdiction,
                   ps.prc_license_no AS psy_prc_license_no, ps.license_expiry AS psy_license_expiry,
                   ps.clinic_name AS psy_clinic_name,
                   ps.accepts_medication_management AS psy_accepts_medication_management,
                   ad.job_title AS admin_job_title, ad.department AS admin_department,
                   ad.is_super_admin AS admin_is_super_admin,
                   iv.status AS verification_status, iv.id_type_name AS verification_id_type,
                   iv.id_number AS verification_id_number,
                   iv.rejection_reason AS verification_rejection_reason,
                   iv.submitted_at AS verification_submitted_at
            FROM users u
            JOIN roles r                ON r.role_id = u.role_id
            LEFT JOIN user_profiles up  ON up.user_id = u.id
            LEFT JOIN clients c         ON c.user_id = u.id
            LEFT JOIN service_types cst ON cst.service_type_id = c.preferred_service_id
            LEFT JOIN professionals p   ON p.user_id = u.id
            LEFT JOIN attorneys at      ON at.user_id = u.id
            LEFT JOIN psychiatrists ps  ON ps.user_id = u.id
            LEFT JOIN admins ad         ON ad.user_id = u.id
            LEFT JOIN LATERAL (
                SELECT v.status, it.name AS id_type_name, v.id_number, v.rejection_reason, v.submitted_at
                FROM identity_verifications v
                JOIN id_types it ON it.id_type_id = v.id_type_id
                WHERE v.user_id = u.id
                ORDER BY v.submitted_at DESC, v.id DESC
                LIMIT 1
            ) iv ON TRUE
            """;

    private final JdbcClient jdbc;
    /** role_permissions is reference data from the migrations; loaded once. */
    private volatile Map<Role, Set<String>> permissionsByRole;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record Credentials(int id, String passwordHash) {
    }

    public record EmailOwner(int id, String email) {
    }

    public record NewClient(String username, String email, String passwordHash, String fullName, String contactNo, String address) {
    }

    /** A self-registered attorney or psychiatrist; {@code licenseNo} is the bar roll or PRC license number. */
    public record NewProfessional(Role role, String username, String email, String passwordHash, String fullName,
                                  String contactNo, String address, String licenseNo, String affiliation) {
        public NewProfessional {
            if (role != Role.ATTORNEY && role != Role.PSYCHIATRIST) {
                throw new IllegalArgumentException("Not a professional role: " + role);
            }
        }
    }

    public Optional<Account> findById(int id) {
        return jdbc.sql(ACCOUNT_SELECT + " WHERE u.id = :id")
                .param("id", id)
                .query(mapper())
                .optional();
    }

    public List<Account> list(int limit) {
        return jdbc.sql(ACCOUNT_SELECT + " ORDER BY u.id ASC LIMIT :limit")
                .param("limit", limit)
                .query(mapper())
                .list();
    }

    /** Login accepts a username, email, or full name (legacy behaviour). */
    public List<Credentials> findCredentialsByLoginId(String loginId) {
        return jdbc.sql("""
                        SELECT u.id, u.password_hash
                        FROM users u LEFT JOIN user_profiles up ON up.user_id = u.id
                        WHERE LOWER(u.username) = LOWER(:loginId) OR LOWER(u.email) = LOWER(:loginId)
                           OR LOWER(up.full_name) = LOWER(:loginId)
                        ORDER BY u.id
                        """)
                .param("loginId", loginId)
                .query((rs, n) -> new Credentials(rs.getInt("id"), rs.getString("password_hash")))
                .list();
    }

    public Optional<EmailOwner> findByEmail(String email) {
        return jdbc.sql("SELECT id, email FROM users WHERE LOWER(email) = LOWER(:email)")
                .param("email", email)
                .query((rs, n) -> new EmailOwner(rs.getInt("id"), rs.getString("email")))
                .optional();
    }

    public void touchLastLogin(int id) {
        jdbc.sql("UPDATE users SET last_login_at = now() WHERE id = :id").param("id", id).update();
    }

    /** Creates the users, user_profiles and clients rows. Call inside a transaction. */
    public int createClient(NewClient client) {
        int id = insertUser(Role.CLIENT, UserStatus.ACTIVE, client.username(), client.email(), client.passwordHash());
        insertProfile(id, client.fullName(), client.contactNo(), client.address());
        jdbc.sql("INSERT INTO clients (user_id, consent_to_terms_at) VALUES (:id, now())").param("id", id).update();
        return id;
    }

    /**
     * Creates the users, user_profiles, professionals and attorneys/psychiatrists rows. The account
     * starts PENDING_VERIFICATION and is not assigned clients until an administrator approves its
     * identity verification ({@link #startAcceptingClients}). Call inside a transaction.
     */
    public int createProfessional(NewProfessional pro) {
        int id = insertUser(pro.role(), UserStatus.PENDING_VERIFICATION, pro.username(), pro.email(), pro.passwordHash());
        insertProfile(id, pro.fullName(), pro.contactNo(), pro.address());
        jdbc.sql("""
                        INSERT INTO professionals (user_id, professional_type, display_title, is_accepting_clients)
                        VALUES (:id, :type, :title, FALSE)
                        """)
                .param("id", id)
                .param("type", pro.role().name())
                .param("title", pro.role() == Role.ATTORNEY ? "Atty." : "Dr.")
                .update();
        String sql = pro.role() == Role.ATTORNEY
                ? "INSERT INTO attorneys (user_id, bar_roll_no, law_firm) VALUES (:id, :licenseNo, :affiliation)"
                : "INSERT INTO psychiatrists (user_id, prc_license_no, clinic_name) VALUES (:id, :licenseNo, :affiliation)";
        jdbc.sql(sql)
                .param("id", id)
                .param("licenseNo", pro.licenseNo())
                .param("affiliation", pro.affiliation())
                .update();
        return id;
    }

    /** Stops assigning new clients to a professional (e.g. while re-verification is pending). */
    public void stopAcceptingClients(int id) {
        jdbc.sql("UPDATE professionals SET is_accepting_clients = FALSE WHERE user_id = :id").param("id", id).update();
    }

    /** Lets an approved professional be assigned new clients. No-op for other roles. */
    public void startAcceptingClients(int id) {
        jdbc.sql("UPDATE professionals SET is_accepting_clients = TRUE WHERE user_id = :id").param("id", id).update();
    }

    private int insertUser(Role role, UserStatus status, String username, String email, String passwordHash) {
        return jdbc.sql("""
                        INSERT INTO users (role_id, username, email, password_hash, status)
                        SELECT role_id, :username, :email, :passwordHash, :status FROM roles WHERE code = :role
                        RETURNING id
                        """)
                .param("username", username)
                .param("email", email)
                .param("passwordHash", passwordHash)
                .param("status", status.name())
                .param("role", role.name())
                .query(Integer.class)
                .single();
    }

    private void insertProfile(int id, String fullName, String contactNo, String address) {
        jdbc.sql("INSERT INTO user_profiles (user_id, full_name, contact_no, address) VALUES (:id, :fullName, :contactNo, :address)")
                .param("id", id)
                .param("fullName", fullName)
                .param("contactNo", contactNo)
                .param("address", address)
                .update();
    }

    /** Null or empty values keep the current column value. */
    public void updateCredentials(int id, String username, String email, String passwordHash) {
        jdbc.sql("""
                        UPDATE users SET
                          username      = COALESCE(NULLIF(CAST(:username AS text), ''), username),
                          email         = COALESCE(NULLIF(CAST(:email AS text), ''), email),
                          password_hash = COALESCE(NULLIF(CAST(:passwordHash AS text), ''), password_hash)
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("username", username)
                .param("email", email)
                .param("passwordHash", passwordHash)
                .update();
    }

    /**
     * Null keeps the current value. Empty clears contact number and address, but keeps the
     * full name and picture.
     */
    public void updateProfile(int id, String fullName, String contactNo, String address, String profilePictureUrl) {
        jdbc.sql("""
                        UPDATE user_profiles SET
                          full_name           = COALESCE(NULLIF(CAST(:fullName AS text), ''), full_name),
                          contact_no          = COALESCE(CAST(:contactNo AS text), contact_no),
                          address             = COALESCE(CAST(:address AS text), address),
                          profile_picture_url = COALESCE(NULLIF(CAST(:pictureUrl AS text), ''), profile_picture_url)
                        WHERE user_id = :id
                        """)
                .param("id", id)
                .param("fullName", fullName)
                .param("contactNo", contactNo)
                .param("address", address)
                .param("pictureUrl", profilePictureUrl)
                .update();
    }

    public void setStatus(int id, UserStatus status) {
        jdbc.sql("UPDATE users SET status = :status WHERE id = :id")
                .param("id", id)
                .param("status", status.name())
                .update();
    }

    /** Active administrators (recipients of contact-form messages). */
    public List<Integer> activeAdminIds() {
        return jdbc.sql("""
                        SELECT u.id FROM users u JOIN roles r ON r.role_id = u.role_id
                        WHERE r.code = 'ADMIN' AND u.status NOT IN ('SUSPENDED', 'DEACTIVATED')
                        """)
                .query(Integer.class)
                .list();
    }

    public int countAll() {
        return jdbc.sql("SELECT COUNT(*)::int FROM users").query(Integer.class).single();
    }

    private AccountRowMapper mapper() {
        return new AccountRowMapper(permissionsByRole());
    }

    private Map<Role, Set<String>> permissionsByRole() {
        Map<Role, Set<String>> cached = permissionsByRole;
        if (cached == null) {
            Map<Role, Set<String>> loaded = new EnumMap<>(Role.class);
            jdbc.sql("""
                            SELECT r.code AS role_code, p.code AS permission_code
                            FROM role_permissions rp
                            JOIN roles r ON r.role_id = rp.role_id
                            JOIN permissions p ON p.permission_id = rp.permission_id
                            """)
                    .query(rs -> {
                        loaded.computeIfAbsent(Role.valueOf(rs.getString("role_code")), r -> new TreeSet<>())
                                .add(rs.getString("permission_code"));
                    });
            loaded.replaceAll((role, codes) -> Set.copyOf(codes));
            cached = Map.copyOf(loaded);
            permissionsByRole = cached;
        }
        return cached;
    }
}
