package com.therapeutica.administrare;

import com.therapeutica.therapeutica_app.admin.AdminController;
import com.therapeutica.therapeutica_app.admin.AdministratorInitialService;
import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class ComandaAdministrareTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.1")
                    .withDatabaseName("therapeutica_comanda_admin_test")
                    .withUsername("test_user")
                    .withPassword("test_password");

    @Test
    void comandaCreeazaAdministratorFaraServerWebHibernateSauFlyway() {
        // Migrarea se execută explicit numai pe containerul acestui test.
        Flyway.configure()
                .dataSource(
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword()
                )
                .locations("classpath:db/migration")
                .load()
                .migrate();

        try (var context = ComandaAdministrare.deschideContext(
                "--spring.config.location=classpath:/application-http-test.yml",
                "--spring.datasource.url=" + postgres.getJdbcUrl(),
                "--spring.datasource.username=" + postgres.getUsername(),
                "--spring.datasource.password=" + postgres.getPassword()
        )) {
            assertThat(context.getBeansOfType(
                    ServletWebServerFactory.class
            )).isEmpty();

            assertThat(context.getBeansOfType(
                    EntityManagerFactory.class
            )).isEmpty();

            assertThat(context.getBeansOfType(Flyway.class)).isEmpty();
            assertThat(context.getBeansOfType(AdminController.class))
                    .isEmpty();

            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);

            assertThat(jdbc.queryForObject(
                    "SELECT current_database()", String.class
            )).isEqualTo(postgres.getDatabaseName());

            var serviciu = context.getBean(
                    AdministratorInitialService.class
            );

            String parola = "Parola-fictiva-Comanda-123!";

            assertThat(serviciu.creeaza(
                    "admin-comanda@example.test",
                    "Administrator",
                    "Test",
                    parola
            )).isTrue();

            assertThat(serviciu.creeaza(
                    "alt-admin@example.test",
                    "Alt",
                    "Administrator",
                    "Alta-parola-fictiva-123!"
            )).isFalse();

            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*)
                    FROM public.utilizatori
                    WHERE rol = 'ADMIN'
                    """, Long.class)).isEqualTo(1L);

            String hash = jdbc.queryForObject("""
                    SELECT parola
                    FROM public.utilizatori
                    WHERE email = ?
                    """, String.class, "admin-comanda@example.test");

            assertThat(hash).isNotEqualTo(parola);
            assertThat(context.getBean(PasswordEncoder.class)
                    .matches(parola, hash)).isTrue();
        }
    }
}