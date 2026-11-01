package io.github.naimelijah.finassist.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariConfig;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.LifecycleProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.web.server.Shutdown;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Pins the shutdown and connection bounds the shipped {@code application.yml} sets, read with no
 * context and no Docker.
 *
 * <p>Each value is bound into the very properties type Spring Boot binds it into at start-up, so a
 * misspelt key, which Boot ignores and replaces with its own default, fails here as surely as a
 * changed value. The system environment and system properties are removed first, so neither a
 * developer's shell nor CI can supply what the file does not.
 */
class LedgerShippedConfigurationTest {

    /**
     * Inside the 30 s {@code stop_grace_period} {@code docker-compose.yml} gives the ledger, with
     * time left for the JVM to exit before Compose sends SIGKILL (#349).
     */
    private static final Duration SHUTDOWN_PHASE_TIMEOUT = Duration.ofSeconds(20);

    /**
     * Below the gateway's 10 s relay read timeout, so a request waiting on an unreachable database
     * fails here before the gateway answers 504 "outcome unknown", rather than committing after it
     * (ADR-067, #349).
     */
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void Given_the_shipped_configuration_When_bound_Then_each_shutdown_phase_ends_within_twenty_seconds()
            throws IOException {
        LifecycleProperties lifecycle = shipped().bindOrCreate("spring.lifecycle", LifecycleProperties.class);

        assertThat(lifecycle.getTimeoutPerShutdownPhase()).isEqualTo(SHUTDOWN_PHASE_TIMEOUT);
    }

    @Test
    void Given_the_shipped_configuration_When_bound_Then_the_server_shuts_down_gracefully() throws IOException {
        ServerProperties server = shipped().bindOrCreate("server", ServerProperties.class);

        assertThat(server.getShutdown())
                .as("an immediate shutdown drops the in-flight request the shutdown phase exists to drain")
                .isEqualTo(Shutdown.GRACEFUL);
    }

    /**
     * Bound into {@link HikariConfig}, whose setters Boot binds {@code spring.datasource.hikari}
     * through, since the pool Boot builds is its subclass {@code HikariDataSource}.
     */
    @Test
    void Given_the_shipped_configuration_When_bound_Then_a_connection_is_awaited_for_at_most_five_seconds()
            throws IOException {
        HikariConfig pool = shipped().bindOrCreate("spring.datasource.hikari", HikariConfig.class);

        assertThat(pool.getConnectionTimeout()).isEqualTo(CONNECTION_TIMEOUT.toMillis());
    }

    private static Binder shipped() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        for (PropertySource<?> loaded :
                new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addLast(loaded);
        }
        return Binder.get(environment);
    }
}
