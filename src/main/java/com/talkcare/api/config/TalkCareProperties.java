package com.talkcare.api.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/** Typed view of the {@code talkcare.*} settings in application.yml (fed by environment variables). */
@Validated
@ConfigurationProperties("talkcare")
public record TalkCareProperties(
        String environment,
        @Valid @NotNull Database database,
        List<String> corsOrigins,
        String publicBaseUrl,
        @Valid @NotNull Jwt jwt,
        @NotNull Path uploadDir,
        @NotNull DataSize maxRequestBytes,
        @NotNull ZoneId timezone,
        String resetUrl,
        @NotNull Smtp smtp,
        @NotNull Seed seed) {

    public TalkCareProperties {
        corsOrigins = corsOrigins == null ? List.of() : corsOrigins.stream().map(String::trim).filter(StringUtils::hasText).toList();
        publicBaseUrl = stripTrailingSlashes(publicBaseUrl);
        resetUrl = stripTrailingSlashes(resetUrl);
        uploadDir = uploadDir.toAbsolutePath().normalize();
    }

    public boolean isProduction() {
        return "production".equalsIgnoreCase(environment);
    }

    private static String stripTrailingSlashes(String value) {
        return value == null ? "" : value.trim().replaceAll("/+$", "");
    }

    public record Database(
            @NotBlank(message = "Missing required environment variable DATABASE_URL. See .env.example.") String url,
            String unpooledUrl,
            @Min(1) int poolMax) {
    }

    /**
     * HS256 needs a key of at least 256 bits, so the secret must be 32+ characters in every
     * environment (the Express version only enforced this in production).
     */
    public record Jwt(
            @NotBlank(message = "Missing required environment variable JWT_SECRET. See .env.example.") String secret,
            @NotNull @DurationUnit(ChronoUnit.SECONDS) Duration expiresIn) {

        public Jwt {
            if (StringUtils.hasText(secret) && (secret.equals("change-me") || secret.trim().length() < 32)) {
                throw new IllegalArgumentException("JWT_SECRET must be a random string of at least 32 characters.");
            }
        }
    }

    /** SMTP settings; when {@code brevoApiKey} is set, mail goes through Brevo's HTTPS API instead. */
    public record Smtp(String host, int port, String username, String password, String from, String brevoApiKey) {

        public boolean usesBrevo() {
            return StringUtils.hasText(brevoApiKey) && StringUtils.hasText(from);
        }

        public boolean isConfigured() {
            return StringUtils.hasText(host) && StringUtils.hasText(username)
                    && StringUtils.hasText(password) && StringUtils.hasText(from);
        }
    }

    public record Seed(
            boolean allowInProduction,
            String clientPassword,
            String attorneyPassword,
            String psychiatristPassword,
            String adminPassword) {
    }
}
