package com.talkcare.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.talkcare.api.task.CliTask;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end check of the HTTP contract the desktop app relies on, against a real PostgreSQL
 * with the Flyway migrations applied.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TalkCareApiIntegrationTest {

    static final String JWT_SECRET = "test-secret-for-integration-tests-0123456789abcdef";
    /** Produced by bcryptjs (Express version) for "LegacyPass#2026". */
    static final String LEGACY_BCRYPT = "$2b$10$9ZLTc5pKif9soTmqO7GTM.OTKTqRL5QZEQHDFj98UJs2qJkSIzGte";
    /** Produced by jsonwebtoken (Express version): sub 999, role CLIENT, expires in 100 years. */
    static final String LEGACY_JWT = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiQ0xJRU5UIiwiaWF0IjoxNzkzNjQyNTc5LCJleHAiOjQ5NDcyNDI1NzksInN1YiI6Ijk5OSJ9.G1CtFRA4nhjCs8BMKQwzeywlHzQvKqjCgytU2PElCgE";
    /** 1x1 PNG. */
    static final String PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

    static EmbeddedPostgres postgres;
    static Path uploads;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        uploads = Files.createTempDirectory("talkcare-uploads");
        registry.add("talkcare.database.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        // A developer's .env may set DATABASE_URL_UNPOOLED; migrations must hit the embedded database.
        registry.add("talkcare.database.unpooled-url", () -> "");
        registry.add("talkcare.jwt.secret", () -> JWT_SECRET);
        registry.add("talkcare.upload-dir", uploads::toString);
    }

    @AfterAll
    static void stop() throws IOException {
        postgres.close();
    }

    @LocalServerPort
    int port;
    @Autowired
    JsonMapper json;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    List<CliTask> tasks;

    final HttpClient http = HttpClient.newHttpClient();
    String clientToken;
    String psychiatristToken;
    String attorneyToken;
    String adminToken;
    int conversationId;
    int reportId;

    record Res(int status, JsonNode body, HttpResponse<String> raw) {
        String error() {
            return body.path("error").asString();
        }
    }

    Res call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body instanceof String s ? s : json.writeValueAsString(body)));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        JsonNode node = text == null || text.isEmpty() || !text.trim().matches("^[\\[{].*") ? json.nullNode() : json.readTree(text);
        return new Res(response.statusCode(), node, response);
    }

    Res get(String path, String token) throws Exception {
        return call("GET", path, token, null);
    }

    Res post(String path, String token, Object body) throws Exception {
        return call("POST", path, token, body);
    }

    String login(String username, String password) throws Exception {
        Res res = post("/api/auth/login", null, java.util.Map.of("username", username, "password", password));
        assertThat(res.status()).as(res.body().toString()).isEqualTo(200);
        return res.body().path("sessionToken").asString();
    }

    @BeforeAll
    void seed() throws Exception {
        tasks.stream().filter(t -> t.name().equals("seed")).findFirst().orElseThrow().run();
    }

    @Test
    @Order(1)
    void publicEndpoints() throws Exception {
        Res root = get("/", null);
        assertThat(root.body().path("name").asString()).isEqualTo("TalkCare API");

        Res health = get("/api/health", null);
        assertThat(health.status()).isEqualTo(200);
        assertThat(health.body().path("database").asString()).isEqualTo("connected");

        Res lookups = get("/api/lookups", "stale-token-is-ignored");
        assertThat(lookups.status()).isEqualTo(200);
        assertThat(lookups.body().path("serviceTypes").get(0).path("code").asString()).isEqualTo("JUSTICE_CARE");
        assertThat(lookups.body().path("idTypes").toString()).contains("Passport");
        assertThat(lookups.body().path("concernTypes").toString()).contains("\"name\":\"Other\",\"serviceType\":null");
    }

    @Test
    @Order(2)
    void loginReturnsAccountWithSubtypeFieldsAndToken() throws Exception {
        Res bad = post("/api/auth/login", "garbage", java.util.Map.of("username", "test.user", "password", "nope"));
        assertThat(bad.status()).isEqualTo(401);
        assertThat(bad.body().path("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(bad.error()).isEqualTo("Invalid username, email, full name, or password.");

        Res missing = post("/api/auth/login", null, java.util.Map.of("username", "test.user"));
        assertThat(missing.status()).isEqualTo(400);
        assertThat(missing.error()).isEqualTo("Enter your username or email and password.");

        Res ok = post("/api/auth/login", null, java.util.Map.of("username", "TalkCare Test User", "password", "TalkCareUser#2026"));
        assertThat(ok.status()).isEqualTo(200);
        JsonNode body = ok.body();
        assertThat(body.path("role").asString()).isEqualTo("CLIENT");
        assertThat(body.path("roleDisplayName").asString()).isEqualTo("Client");
        assertThat(body.path("dashboardView").asString()).isEqualTo("dashboard");
        assertThat(body.path("status").asString()).isEqualTo("VERIFIED");
        assertThat(body.has("preferredService")).isTrue();
        assertThat(body.path("preferredService").isNull()).isTrue();
        assertThat(body.path("verificationStatus").isNull()).isTrue();
        assertThat(body.path("idType").asString()).isEmpty();
        assertThat(body.path("permissions").toString()).contains("reports.create", "messages.send");
        assertThat(body.path("createdAt").asString()).endsWith("Z");
        clientToken = body.path("sessionToken").asString();

        Res admin = post("/api/auth/login", null, java.util.Map.of("email", "test.admin@talkcare.com", "password", "TalkCareAdmin#2026"));
        assertThat(admin.body().path("roleDisplayName").asString()).isEqualTo("Administrator");
        assertThat(admin.body().path("isSuperAdmin").asBoolean()).isTrue();
        assertThat(admin.body().path("dashboardView").asString()).isEqualTo("admin");
        adminToken = admin.body().path("sessionToken").asString();

        Res psy = post("/api/auth/login", null, java.util.Map.of("username", "test.psychiatrist", "password", "TalkCareTherapist#2026"));
        assertThat(psy.body().path("serviceType").asString()).isEqualTo("THOMATT");
        assertThat(psy.body().path("displayTitle").asString()).isEqualTo("Dr.");
        assertThat(psy.body().path("prcLicenseNo").asString()).isEqualTo("PRC-0001");
        assertThat(psy.body().path("dashboardView").asString()).isEqualTo("professional-dashboard");
        psychiatristToken = psy.body().path("sessionToken").asString();

        attorneyToken = login("test.attorney", "TalkCareCaseHandler#2026");
    }

    @Test
    @Order(3)
    void sessionsAndLegacyCredentials() throws Exception {
        Res noToken = get("/api/auth/me", null);
        assertThat(noToken.status()).isEqualTo(401);
        assertThat(noToken.error()).isEqualTo("Please sign in to continue.");

        Res badToken = get("/api/auth/me", "not-a-jwt");
        assertThat(badToken.status()).isEqualTo(401);
        assertThat(badToken.error()).isEqualTo("Your session has expired. Please sign in again.");

        HttpResponse<String> viaHeader = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/me"))
                .header("X-Session-Token", clientToken).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(viaHeader.statusCode()).isEqualTo(200);

        // An account created by the Express version: bcryptjs hash and jsonwebtoken session.
        jdbc.sql("INSERT INTO users (id, role_id, username, email, password_hash, status) "
                        + "SELECT 999, role_id, 'legacy.user', 'legacy@talkcare.com', :hash, 'ACTIVE' FROM roles WHERE code = 'CLIENT'")
                .param("hash", LEGACY_BCRYPT).update();
        jdbc.sql("INSERT INTO user_profiles (user_id, full_name) VALUES (999, 'Legacy User')").update();
        jdbc.sql("INSERT INTO clients (user_id) VALUES (999)").update();

        Res legacySession = get("/api/auth/me", LEGACY_JWT);
        assertThat(legacySession.status()).isEqualTo(200);
        assertThat(legacySession.body().path("username").asString()).isEqualTo("legacy.user");
        assertThat(login("legacy.user", "LegacyPass#2026")).isNotBlank();

        jdbc.sql("UPDATE users SET status = 'SUSPENDED' WHERE id = 999").update();
        assertThat(get("/api/auth/me", LEGACY_JWT).status()).isEqualTo(401);
        Res suspendedLogin = post("/api/auth/login", null, java.util.Map.of("username", "legacy.user", "password", "LegacyPass#2026"));
        assertThat(suspendedLogin.error()).isEqualTo("This account is suspended or deactivated.");
    }

    @Test
    @Order(4)
    void registration() throws Exception {
        Res incomplete = post("/api/auth/register", null, java.util.Map.of("username", "new.user", "password", "Password#1"));
        assertThat(incomplete.status()).isEqualTo(400);
        assertThat(incomplete.error()).isEqualTo("Please complete all required account fields.");

        var body = new java.util.HashMap<String, Object>(java.util.Map.of(
                "username", " new.user ", "fullName", "New User", "email", "new@talkcare.com",
                "contactNo", "0917", "address", "Batangas", "password", "short"));
        Res weak = post("/api/auth/register", null, body);
        assertThat(weak.error()).isEqualTo("Password must be at least 8 characters long.");

        body.put("email", "not-an-email");
        assertThat(post("/api/auth/register", null, body).error()).isEqualTo("Please enter a valid email address.");

        body.put("email", "new@talkcare.com");
        body.put("password", "Password#1");
        Res created = post("/api/auth/register", null, body);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("username").asString()).isEqualTo("new.user");
        assertThat(created.body().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(created.body().path("sessionToken").asString()).isNotBlank();

        Res duplicate = post("/api/auth/register", null, body);
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.body().path("code").asString()).isEqualTo("CONFLICT");
        assertThat(duplicate.error()).isEqualTo("That value is already in use (username, email, or license number).");

        Res invalidJson = post("/api/auth/register", null, "{oops");
        assertThat(invalidJson.status()).isEqualTo(400);
        assertThat(invalidJson.error()).isEqualTo("The request body is not valid JSON.");
    }

    @Test
    @Order(5)
    void conversationFlow() throws Exception {
        Res invalid = post("/api/conversations", clientToken, java.util.Map.of("serviceType", "NOPE"));
        assertThat(invalid.error()).isEqualTo("Please choose a valid TalkCare service.");

        Res opened = post("/api/conversations", clientToken, java.util.Map.of("serviceType", "THOMATT"));
        assertThat(opened.status()).isEqualTo(200);
        assertThat(opened.body().path("professionalName").asString()).isEqualTo("Dr. Eleanor Reyes");
        assertThat(opened.body().path("professionalType").asString()).isEqualTo("PSYCHIATRIST");
        assertThat(opened.body().path("title").asString()).isEqualTo("ThoMatt Consultation");
        conversationId = opened.body().path("id").asInt();

        Res again = post("/api/conversations", clientToken, java.util.Map.of("serviceType", "THOMATT"));
        assertThat(again.body().path("id").asInt()).isEqualTo(conversationId);

        Res history = get("/api/conversations/" + conversationId + "/messages", clientToken);
        assertThat(history.body()).hasSize(1);
        assertThat(history.body().get(0).path("messageText").asString()).startsWith("Welcome to ThoMatt. I am Dr. Eleanor Reyes.");
        assertThat(history.body().get(0).path("senderRole").asString()).isEqualTo("PROFESSIONAL");

        Res empty = post("/api/conversations/" + conversationId + "/messages", clientToken, java.util.Map.of("messageText", "  "));
        assertThat(empty.error()).isEqualTo("Write a message or attach a photo before sending.");

        Res badImage = post("/api/conversations/" + conversationId + "/messages", clientToken,
                java.util.Map.of("attachmentData", "data:image/gif;base64,R0lGODlh"));
        assertThat(badImage.error()).isEqualTo("Please upload the photo as PNG, JPG.");

        Res sent = post("/api/conversations/" + conversationId + "/messages", clientToken,
                java.util.Map.of("messageText", "Hello doctor", "attachmentData", PNG));
        assertThat(sent.status()).isEqualTo(201);
        assertThat(sent.body()).hasSize(2);
        assertThat(sent.body().get(0).path("senderRole").asString()).isEqualTo("CLIENT");
        String url = sent.body().get(0).path("attachmentUrl").asString();
        assertThat(url).startsWith("/uploads/chat_").endsWith(".png");
        assertThat(sent.body().get(0).path("attachments").get(0).path("mimeType").asString()).isEqualTo("image/png");
        assertThat(sent.body().get(1).path("senderRole").asString()).isEqualTo("SYSTEM");
        assertThat(sent.body().get(1).path("senderName").asString()).isEqualTo("TalkCare Auto-Assistant");
        assertThat(sent.body().get(1).path("senderId").isNull()).isTrue();

        HttpResponse<byte[]> image = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + url)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(get("/uploads/missing.png", null).body().path("code").asString()).isEqualTo("HTTP_ERROR");

        Res assigned = get("/api/conversations/assigned", psychiatristToken);
        assertThat(assigned.body()).hasSize(1);
        assertThat(assigned.body().get(0).path("clientName").asString()).isEqualTo("TalkCare Test User");

        Res reply = post("/api/conversations/" + conversationId + "/replies", psychiatristToken, java.util.Map.of("messageText", "Hi"));
        assertThat(reply.status()).isEqualTo(201);
        assertThat(reply.body().path("senderName").asString()).isEqualTo("Dr. Eleanor Reyes");
        Res clientNotifications = get("/api/notifications", clientToken);
        assertThat(clientNotifications.body().get(0).path("type").asString()).isEqualTo("MESSAGE");
        assertThat(clientNotifications.body().get(0).path("message").asString())
                .isEqualTo("Dr. Eleanor Reyes replied to your ThoMatt Consultation conversation.");

        assertThat(get("/api/conversations/" + conversationId, attorneyToken).status()).isEqualTo(404);
        assertThat(post("/api/conversations/" + conversationId + "/replies", clientToken, java.util.Map.of("messageText", "x")).status())
                .isEqualTo(403);
        assertThat(get("/api/conversations/abc", clientToken).error()).isEqualTo("Invalid conversation id.");
        assertThat(get("/api/conversations/" + conversationId, adminToken).status()).isEqualTo(200);
        assertThat(get("/api/conversations/assigned", adminToken).body()).hasSize(1);
    }

    @Test
    @Order(6)
    void reportsAndVerification() throws Exception {
        var report = java.util.Map.of("fullName", "TalkCare Test User", "email", "test.user@talkcare.com",
                "address", "Batangas", "contactNo", "0917", "concernType", "anxiety / depression",
                "concernDetails", "I need help");
        Res created = post("/api/reports", clientToken, report);
        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body().path("reportCode").asString()).isEqualTo("RPT-0001");
        assertThat(created.body().path("serviceType").asString()).isEqualTo("THOMATT");
        assertThat(created.body().path("statusLabel").asString()).isEqualTo("Under Review");
        assertThat(created.body().path("involvedWitness").asString()).isEmpty();
        reportId = created.body().path("id").asInt();

        assertThat(post("/api/reports", psychiatristToken, report).status()).isEqualTo(403);
        assertThat(get("/api/reports?status=under%20review", clientToken).body()).hasSize(1);
        assertThat(get("/api/reports?filter=RESOLVED", clientToken).body()).isEmpty();
        assertThat(get("/api/reports?status=bogus", clientToken).error()).isEqualTo("Unknown report status filter.");
        assertThat(get("/api/reports/" + reportId, attorneyToken).status()).isEqualTo(404);
        assertThat(get("/api/reports/" + reportId, adminToken).status()).isEqualTo(200);

        Res verification = post("/api/verifications", clientToken, java.util.Map.of(
                "idType", "passport", "idNumber", "P123", "address", "Lipa City", "email", "test.user@talkcare.com",
                "idDocImage", PNG));
        assertThat(verification.status()).as(verification.body().toString()).isEqualTo(200);
        assertThat(verification.body().path("status").asString()).isEqualTo("PENDING_VERIFICATION");

        Res me = get("/api/auth/me", clientToken);
        assertThat(me.body().path("verificationStatus").asString()).isEqualTo("PENDING");
        assertThat(me.body().path("idType").asString()).isEqualTo("Passport");
        assertThat(me.body().path("address").asString()).isEqualTo("Lipa City");
    }

    @Test
    @Order(7)
    void adminDashboard() throws Exception {
        assertThat(get("/api/admin/metrics", clientToken).status()).isEqualTo(403);
        assertThat(get("/api/admin/metrics", clientToken).error()).isEqualTo("You do not have permission to perform this action.");

        Res metrics = get("/api/admin/metrics", adminToken);
        assertThat(metrics.body().path("pendingApprovals").asInt()).isEqualTo(2);
        assertThat(metrics.body().has("sessionToday")).isTrue();

        Res pending = get("/api/admin/pending-requests", adminToken);
        assertThat(pending.body()).hasSize(2);
        JsonNode verification = pending.body().get(0).path("type").asString().equals("VERIFICATION")
                ? pending.body().get(0) : pending.body().get(1);
        assertThat(verification.path("imageUrl").asString()).startsWith("/uploads/id_");
        assertThat(verification.has("reportCode")).isFalse();

        Res noReason = post("/api/admin/pending-requests/action", adminToken,
                java.util.Map.of("type", "VERIFICATION", "id", verification.path("id").asInt(), "action", "REJECT"));
        assertThat(noReason.error()).isEqualTo("A rejection reason is required.");

        Res approve = post("/api/admin/pending-requests/action", adminToken,
                java.util.Map.of("type", "VERIFICATION", "id", verification.path("id").asInt(), "action", "APPROVE"));
        assertThat(approve.body().path("success").asBoolean()).isTrue();
        Res again = post("/api/admin/pending-requests/action", adminToken,
                java.util.Map.of("type", "VERIFICATION", "id", String.valueOf(verification.path("id").asInt()), "action", "APPROVE"));
        assertThat(again.status()).isEqualTo(409);

        Res remarks = post("/api/admin/pending-requests/action", adminToken,
                java.util.Map.of("type", "REPORT", "id", reportId, "action", "ADD_REMARKS", "remarks", "Looking into it"));
        assertThat(remarks.status()).isEqualTo(200);
        Res progress = post("/api/admin/pending-requests/action", adminToken,
                java.util.Map.of("type", "REPORT", "id", reportId, "action", "IN_PROGRESS"));
        assertThat(progress.status()).isEqualTo(200);

        Res resolved = call("PATCH", "/api/reports/" + reportId + "/status", adminToken,
                java.util.Map.of("status", "Resolved", "remarks", "Done"));
        assertThat(resolved.body().path("status").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.body().path("remarks").asString()).isEqualTo("Looking into it\nDone");

        Res activity = get("/api/admin/activity", adminToken);
        assertThat(activity.body().get(0).path("details").asString())
                .isEqualTo("Report RPT-0001 marked Resolved by TalkCare Test Admin. Remarks: Done");
        assertThat(activity.body().get(0).path("actorName").asString()).isEqualTo("TalkCare Test Admin");

        // The report went to the client's psychiatrist, who can reopen it after it was resolved.
        assertThat(resolved.body().path("recipient").asString()).isEqualTo("PSYCHIATRIST");
        assertThat(get("/api/reports", psychiatristToken).body()).hasSize(1);
        Res reopened = call("PATCH", "/api/reports/" + reportId + "/status", psychiatristToken,
                java.util.Map.of("status", "In Progress"));
        assertThat(reopened.body().path("status").asString()).isEqualTo("IN_PROGRESS");
        assertThat(call("PATCH", "/api/reports/" + reportId + "/status", attorneyToken,
                java.util.Map.of("status", "Resolved")).status()).isEqualTo(404);
        assertThat(get("/api/notifications", adminToken).body().get(0).path("message").asString())
                .isEqualTo("Report RPT-0001 marked In Progress by Dr. Eleanor Reyes");

        Res users = get("/api/admin/users", adminToken);
        assertThat(users.body().size()).isGreaterThanOrEqualTo(5);
        assertThat(users.body().toString()).contains("\"barRollNo\":\"BAR-0001\"");

        Res schedule = get("/api/admin/schedule?year=2026&month=11", adminToken);
        assertThat(schedule.body().path("monthName").asString()).isEqualTo("November");
        assertThat(get("/api/admin/schedule?month=13", adminToken).error()).isEqualTo("Invalid month.");

        Res notifications = get("/api/notifications", clientToken);
        assertThat(notifications.body().get(0).path("is_read").asBoolean()).isFalse();
        assertThat(notifications.body().get(0).has("created_at")).isTrue();
        assertThat(post("/api/notifications/read", clientToken, null).body().path("success").asBoolean()).isTrue();
        assertThat(get("/api/notifications", clientToken).body().get(0).path("is_read").asBoolean()).isTrue();
        assertThat(get("/api/auth/me", clientToken).body().path("status").asString()).isEqualTo("VERIFIED");
    }

    @Test
    @Order(8)
    void profileAndErrors() throws Exception {
        Res updated = call("PATCH", "/api/profile", clientToken, java.util.Map.of("fullName", "Renamed User", "contactNo", ""));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.body().path("fullName").asString()).isEqualTo("Renamed User");
        assertThat(updated.body().path("contactNo").asString()).isEmpty();
        assertThat(updated.body().path("address").asString()).isEqualTo("Lipa City");

        Res photo = post("/api/profile/photo", clientToken, java.util.Map.of("image", PNG));
        assertThat(photo.body().path("url").asString()).startsWith("/uploads/profile_");
        Res photo2 = post("/api/profile/photo", clientToken, java.util.Map.of("image", PNG));
        String first = photo.body().path("url").asString();
        assertThat(Files.exists(uploads.resolve(first.substring(first.lastIndexOf('/') + 1)))).isFalse();
        assertThat(photo2.status()).isEqualTo(200);

        Res unknown = get("/api/does-not-exist", clientToken);
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.error()).isEqualTo("Unknown endpoint: GET /api/does-not-exist");

        String huge = "{\"image\":\"" + "A".repeat(16 * 1024 * 1024) + "\"}";
        Res tooLarge = post("/api/profile/photo", clientToken, huge);
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(tooLarge.body().path("code").asString()).isEqualTo("PAYLOAD_TOO_LARGE");

        Res cors = call("OPTIONS", "/api/auth/login", null, null);
        assertThat(cors.status()).isLessThan(400);

        assertThat(Base64.getDecoder().decode(PNG.substring(PNG.indexOf(',') + 1))).isNotEmpty();
        assertThat(post("/api/auth/logout", null, null).body().path("success").asBoolean()).isTrue();
    }

    @Test
    @Order(9)
    void professionalRegistrationAndRoleSignIn() throws Exception {
        var body = new java.util.HashMap<String, Object>(java.util.Map.of(
                "username", "new.attorney", "fullName", "Atty. New Counsel", "email", "attorney@talkcare.com",
                "contactNo", "0918", "address", "Lipa City", "password", "Password#1"));
        Res noLicense = post("/api/auth/register/attorney", null, body);
        assertThat(noLicense.status()).isEqualTo(400);
        assertThat(noLicense.error()).isEqualTo("Please enter your license number.");

        body.put("licenseNo", "ROLL-7788");
        body.put("affiliation", "Lipa Legal Aid");
        Res created = post("/api/auth/register/attorney", null, body);
        assertThat(created.status()).as(created.body().toString()).isEqualTo(201);
        assertThat(created.body().path("role").asString()).isEqualTo("ATTORNEY");
        assertThat(created.body().path("status").asString()).isEqualTo("PENDING_VERIFICATION");
        assertThat(created.body().path("barRollNo").asString()).isEqualTo("ROLL-7788");
        assertThat(created.body().path("lawFirm").asString()).isEqualTo("Lipa Legal Aid");
        assertThat(created.body().path("acceptingClients").asBoolean()).isFalse();
        assertThat(created.body().path("permissions").toString()).contains("verifications.submit", "messages.reply");
        String token = created.body().path("sessionToken").asString();

        body.put("username", "other.attorney");
        body.put("email", "other.attorney@talkcare.com");
        assertThat(post("/api/auth/register/attorney", null, body).status()).as("duplicate bar roll").isEqualTo(409);

        var psy = new java.util.HashMap<String, Object>(body);
        psy.putAll(java.util.Map.of("username", "new.psy", "email", "psy@talkcare.com", "licenseNo", "PRC-9911"));
        Res psychiatrist = post("/api/auth/register/psychiatrist", null, psy);
        assertThat(psychiatrist.status()).isEqualTo(201);
        assertThat(psychiatrist.body().path("prcLicenseNo").asString()).isEqualTo("PRC-9911");
        assertThat(psychiatrist.body().path("dashboardView").asString()).isEqualTo("professional-dashboard");

        Res wrongPortal = post("/api/auth/login", null,
                java.util.Map.of("username", "new.attorney", "password", "Password#1", "role", "CLIENT"));
        assertThat(wrongPortal.status()).isEqualTo(401);
        assertThat(wrongPortal.error()).isEqualTo("This is not a Client account. Use the Attorney sign-in page.");
        Res rightPortal = post("/api/auth/login", null,
                java.util.Map.of("username", "new.attorney", "password", "Password#1", "role", "ATTORNEY"));
        assertThat(rightPortal.status()).isEqualTo(200);

        Res verification = post("/api/verifications", token, java.util.Map.of(
                "idType", "PRC ID", "idNumber", "IBP-1", "address", "Lipa City", "email", "attorney@talkcare.com",
                "idDocImage", PNG));
        assertThat(verification.status()).as(verification.body().toString()).isEqualTo(200);
        JsonNode pending = null;
        for (JsonNode item : get("/api/admin/pending-requests", adminToken).body()) {
            if ("ATTORNEY".equals(item.path("role").asString())) {
                pending = item;
            }
        }
        assertThat(pending).isNotNull();
        assertThat(pending.path("licenseNo").asString()).isEqualTo("ROLL-7788");
        Res approve = post("/api/admin/pending-requests/action", adminToken,
                java.util.Map.of("type", "VERIFICATION", "id", pending.path("id").asInt(), "action", "APPROVE"));
        assertThat(approve.status()).isEqualTo(200);
        Res me = get("/api/auth/me", token);
        assertThat(me.body().path("status").asString()).isEqualTo("VERIFIED");
        assertThat(me.body().path("acceptingClients").asBoolean()).isTrue();
    }

    @Test
    @Order(10)
    void appointments() throws Exception {
        java.time.LocalDateTime when = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Manila")).plusDays(2).atTime(10, 0);
        var body = new java.util.HashMap<String, Object>(java.util.Map.of(
                "serviceType", "THOMATT", "start", when.toString(), "durationMinutes", 60, "notes", "First session"));
        assertThat(post("/api/appointments", psychiatristToken, body).status()).isEqualTo(403);

        Res past = post("/api/appointments", clientToken, java.util.Map.of("serviceType", "THOMATT",
                "start", when.minusDays(5).toString()));
        assertThat(past.error()).isEqualTo("Please choose a time at least 30 minutes from now.");
        assertThat(post("/api/appointments", clientToken, java.util.Map.of("serviceType", "THOMATT",
                "start", when.toString(), "durationMinutes", 20)).error()).isEqualTo("Sessions last 30, 45, 60 or 90 minutes.");

        Res booked = post("/api/appointments", clientToken, body);
        assertThat(booked.status()).as(booked.body().toString()).isEqualTo(201);
        assertThat(booked.body().path("status").asString()).isEqualTo("SCHEDULED");
        assertThat(booked.body().path("statusLabel").asString()).isEqualTo("Requested");
        assertThat(booked.body().path("professionalName").asString()).contains("Eleanor Reyes");
        int id = booked.body().path("id").asInt();

        Res sameSlot = post("/api/appointments", clientToken, body);
        assertThat(sameSlot.status()).isEqualTo(409);
        assertThat(sameSlot.error()).isEqualTo("That time slot is already booked.");
        body.put("start", when.plusMinutes(30).toString());
        assertThat(post("/api/appointments", clientToken, body).error()).as("overlap").isEqualTo("That time slot is already booked.");

        assertThat(get("/api/appointments", psychiatristToken).body()).hasSize(1);
        assertThat(get("/api/appointments", attorneyToken).body()).isEmpty();
        assertThat(get("/api/appointments?date=" + when.toLocalDate(), adminToken).body()).hasSize(1);
        assertThat(get("/api/appointments?date=nope", adminToken).status()).isEqualTo(400);
        assertThat(get("/api/notifications", psychiatristToken).body().toString()).contains("requested a session");

        assertThat(call("PATCH", "/api/appointments/" + id + "/status", clientToken,
                java.util.Map.of("status", "CONFIRMED")).status()).isEqualTo(403);
        assertThat(call("PATCH", "/api/appointments/" + id + "/status", attorneyToken,
                java.util.Map.of("status", "CONFIRMED")).status()).isEqualTo(404);
        Res confirmed = call("PATCH", "/api/appointments/" + id + "/status", psychiatristToken,
                java.util.Map.of("status", "CONFIRMED"));
        assertThat(confirmed.body().path("statusLabel").asString()).isEqualTo("Confirmed");
        Res cancelled = call("PATCH", "/api/appointments/" + id + "/status", clientToken,
                java.util.Map.of("status", "CANCELLED"));
        assertThat(cancelled.body().path("status").asString()).isEqualTo("CANCELLED");
        assertThat(call("PATCH", "/api/appointments/" + id + "/status", psychiatristToken,
                java.util.Map.of("status", "COMPLETED")).status()).isEqualTo(409);
        assertThat(get("/api/notifications", clientToken).body().toString()).contains("is now confirmed");
    }

    @Test
    @Order(11)
    void contactAndAccountStatus() throws Exception {
        assertThat(post("/api/contact", null, java.util.Map.of("name", "Visitor")).error())
                .isEqualTo("Please enter your name, email and message.");
        Res sent = post("/api/contact", null, java.util.Map.of(
                "name", "Visitor", "email", "visitor@example.com", "message", "How do I book a session?"));
        assertThat(sent.status()).as(sent.body().toString()).isEqualTo(200);
        assertThat(get("/api/notifications", adminToken).body().toString()).contains("How do I book a session?");

        String psyToken = login("new.psy", "Password#1");
        int psyId = get("/api/auth/me", psyToken).body().path("id").asInt();
        int adminId = get("/api/auth/me", adminToken).body().path("id").asInt();

        assertThat(call("PATCH", "/api/admin/users/" + psyId + "/status", clientToken,
                java.util.Map.of("action", "SUSPEND")).status()).isEqualTo(403);
        assertThat(call("PATCH", "/api/admin/users/" + adminId + "/status", adminToken,
                java.util.Map.of("action", "SUSPEND")).error()).isEqualTo("You cannot change the status of your own account.");
        Res suspended = call("PATCH", "/api/admin/users/" + psyId + "/status", adminToken, java.util.Map.of("action", "SUSPEND"));
        assertThat(suspended.body().path("status").asString()).isEqualTo("SUSPENDED");
        assertThat(get("/api/auth/me", psyToken).status()).isEqualTo(401);
        assertThat(post("/api/auth/login", null, java.util.Map.of("username", "new.psy", "password", "Password#1")).status())
                .isEqualTo(401);
        Res back = call("PATCH", "/api/admin/users/" + psyId + "/status", adminToken, java.util.Map.of("action", "REACTIVATE"));
        assertThat(back.body().path("status").asString()).isEqualTo("ACTIVE");
        assertThat(get("/api/notifications", login("new.psy", "Password#1")).body().toString()).contains("reactivated");

        // A verified client who is suspended and reactivated goes back into the review queue,
        // and cannot use services until an administrator approves the account again.
        int clientId = get("/api/auth/me", clientToken).body().path("id").asInt();
        call("PATCH", "/api/admin/users/" + clientId + "/status", adminToken, java.util.Map.of("action", "SUSPEND"));
        Res requeued = call("PATCH", "/api/admin/users/" + clientId + "/status", adminToken,
                java.util.Map.of("action", "REACTIVATE"));
        assertThat(requeued.body().path("status").asString()).isEqualTo("PENDING_VERIFICATION");
        JsonNode request = null;
        for (JsonNode item : get("/api/admin/pending-requests", adminToken).body()) {
            if (item.path("type").asString().equals("VERIFICATION") && item.path("userId").asInt() == clientId) {
                request = item;
            }
        }
        assertThat(request).isNotNull();
        String token = login("test.user", "TalkCareUser#2026");
        Res blocked = post("/api/conversations", token, java.util.Map.of("serviceType", "THOMATT"));
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(post("/api/admin/pending-requests/action", adminToken, java.util.Map.of(
                "type", "VERIFICATION", "id", request.path("id").asInt(), "action", "APPROVE")).status()).isEqualTo(200);
        assertThat(post("/api/conversations", token, java.util.Map.of("serviceType", "THOMATT")).status()).isEqualTo(200);
    }

    @Test
    @Order(12)
    void adminApprovedPasswordReset() throws Exception {
        Res unknown = post("/api/auth/forgot-password", null,
                java.util.Map.of("email", "nobody@talkcare.com", "newPassword", "NewPass#2026"));
        assertThat(unknown.status()).isEqualTo(200);
        assertThat(post("/api/auth/forgot-password", null,
                java.util.Map.of("email", "psy@talkcare.com", "newPassword", "short")).error())
                .isEqualTo("Password must be at least 8 characters long.");
        assertThat(post("/api/auth/forgot-password", null,
                java.util.Map.of("email", "psy@talkcare.com", "newPassword", "Rejected#2026")).status()).isEqualTo(200);
        Res requested = post("/api/auth/forgot-password", null,
                java.util.Map.of("email", "PSY@talkcare.com", "newPassword", "NewPass#2026"));
        assertThat(requested.status()).as(requested.body().toString()).isEqualTo(200);

        JsonNode reset = null;
        int count = 0;
        for (JsonNode item : get("/api/admin/pending-requests", adminToken).body()) {
            if (item.path("type").asString().equals("PASSWORD_RESET")) {
                reset = item;
                count++;
            }
        }
        assertThat(count).as("a newer request replaces the open one").isEqualTo(1);
        assertThat(reset.path("email").asString()).isEqualTo("psy@talkcare.com");
        assertThat(reset.has("passwordHash")).isFalse();
        int id = reset.path("id").asInt();

        assertThat(post("/api/admin/pending-requests/action", clientToken,
                java.util.Map.of("type", "PASSWORD_RESET", "id", id, "action", "APPROVE")).status()).isEqualTo(403);
        assertThat(login("new.psy", "Password#1")).as("unchanged until approved").isNotBlank();

        Res approve = post("/api/admin/pending-requests/action", adminToken,
                java.util.Map.of("type", "PASSWORD_RESET", "id", id, "action", "APPROVE"));
        assertThat(approve.status()).as(approve.body().toString()).isEqualTo(200);
        assertThat(post("/api/admin/pending-requests/action", adminToken,
                java.util.Map.of("type", "PASSWORD_RESET", "id", id, "action", "APPROVE")).status()).isEqualTo(409);
        assertThat(post("/api/auth/login", null, java.util.Map.of("username", "new.psy", "password", "Password#1")).status())
                .isEqualTo(401);
        assertThat(post("/api/auth/login", null, java.util.Map.of("username", "new.psy", "password", "Rejected#2026")).status())
                .isEqualTo(401);
        assertThat(get("/api/notifications", login("new.psy", "NewPass#2026")).body().toString())
                .contains("approved your password reset");
    }
}
