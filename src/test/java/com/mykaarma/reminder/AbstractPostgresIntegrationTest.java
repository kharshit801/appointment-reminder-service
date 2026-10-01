package com.mykaarma.reminder;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Base class that boots a real Postgres via Testcontainers. We test against real
 * Postgres (not H2) because the idempotency guarantees rely on Postgres-specific
 * behaviour: {@code FOR UPDATE SKIP LOCKED} and the partial unique index. H2 does
 * not faithfully reproduce these, so it would give false confidence.
 *
 * <p>The scheduler is disabled (very long poll interval) so tests can drive the
 * dispatcher deterministically instead of racing the background thread.</p>
 */
@SpringBootTest
@Testcontainers
public abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("appointments")
                    .withUsername("appointments")
                    .withPassword("appointments");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Effectively disable the background scheduler during tests.
        registry.add("reminder.poll-interval-ms", () -> "3600000");
    }
}
