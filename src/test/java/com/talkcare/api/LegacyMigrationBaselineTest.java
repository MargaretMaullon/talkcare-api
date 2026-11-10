package com.talkcare.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.talkcare.api.config.LegacyMigrationBaseline;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

/** A database migrated by the Express runner is adopted by Flyway without re-running anything. */
class LegacyMigrationBaselineTest {

    @Test
    void baselinesAtTheLastLegacyMigrationAndAppliesOnlyNewerOnes() throws Exception {
        try (EmbeddedPostgres postgres = EmbeddedPostgres.builder().start()) {
            DataSource dataSource = postgres.getPostgresDatabase();
            // Reproduce scripts/migrate.js after applying 001..004 (005 still pending).
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                sql.execute("CREATE TABLE schema_migrations (version TEXT PRIMARY KEY, applied_at TIMESTAMPTZ NOT NULL DEFAULT now())");
                String[] legacy = {"V1__schema", "V2__integrity_triggers", "V3__reference_data", "V4__hardening"};
                for (int i = 0; i < legacy.length; i++) {
                    sql.execute(new ClassPathResource("db/migration/" + legacy[i] + ".sql").getContentAsString(StandardCharsets.UTF_8));
                    sql.execute("INSERT INTO schema_migrations VALUES ('00" + (i + 1) + "_" + legacy[i].substring(4) + ".sql')");
                }
            }

            Flyway flyway = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
            new LegacyMigrationBaseline().migrate(flyway);

            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForList("SELECT version || ':' || type FROM flyway_schema_history ORDER BY installed_rank", String.class))
                    .containsExactly("4:BASELINE", "5:SQL", "6:SQL", "7:SQL");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM concern_types WHERE name = 'Mental Health Crisis'", Integer.class))
                    .isEqualTo(1);

            // Second start: nothing to do.
            new LegacyMigrationBaseline().migrate(flyway);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Integer.class)).isEqualTo(4);
        }
    }
}
