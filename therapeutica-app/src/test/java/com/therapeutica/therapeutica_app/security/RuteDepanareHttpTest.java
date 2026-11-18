package com.therapeutica.therapeutica_app.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-http-test.yml"
})
@AutoConfigureMockMvc
@ActiveProfiles("http-test")
@Testcontainers
class RuteDepanareHttpTest {

    private static final List<String> RUTE_RETRASE = List.of(
            "/api/test",
            "/api/test/db-connection",
            "/api/test/health",
            "/api/test/genereaza-hash/parola-fictiva",
            "/api/test/test-parola/fictiv@example.test/parola-fictiva",
            "/debug-env"
    );

    private static final Path DIRECTOR_DOCUMENTE = Path.of(
            System.getProperty("java.io.tmpdir"),
            "therapeutica-debug-routes-test-" + UUID.randomUUID()
    ).toAbsolutePath();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.1")
                    .withDatabaseName("therapeutica_debug_routes_test")
                    .withUsername("test_user")
                    .withPassword("test_password");

    @DynamicPropertySource
    static void configureazaStocarea(DynamicPropertyRegistry registry) {
        registry.add(
                "app.storage.documents-dir",
                DIRECTOR_DOCUMENTE::toString
        );
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping requestMappingHandlerMapping;

    @MockitoBean
    private JavaMailSender mailSender;

    @BeforeEach
    void verificaBazaTemporara() {
        assertThat(jdbc.queryForObject(
                "SELECT current_database()",
                String.class
        )).isEqualTo(postgres.getDatabaseName());
    }

    @Test
    void aplicatiaNuMaiInregistreazaRuteleDeDepanare() {
        var ruteInregistrate = requestMappingHandlerMapping
                .getHandlerMethods()
                .keySet()
                .stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .toList();

        assertThat(ruteInregistrate)
                .noneMatch(ruta ->
                        ruta.equals("/api/test")
                                || ruta.startsWith("/api/test/")
                                || ruta.equals("/debug-env")
                                || ruta.startsWith("/debug-env/")
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {"PACIENT", "MEDIC"})
    void ruteleDeDepanareSuntRefuzateUtilizatorilorAutentificati(
            String rol) throws Exception {

        // Simulăm identitatea pentru a verifica regulile reale de acces.
        // Nu avem nevoie de utilizatori sau documente în baza de test.
        for (String ruta : RUTE_RETRASE) {
            mockMvc.perform(
                            get(ruta)
                                    .with(user("utilizator-test").roles(rol))
                                    .accept(MediaType.TEXT_HTML)
                    )
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void vizitatorulNuPoateAccesaRuteleDeDepanare() throws Exception {
        for (String ruta : RUTE_RETRASE) {
            mockMvc.perform(
                            get(ruta)
                                    .accept(MediaType.TEXT_HTML)
                    )
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost/login"));
        }
    }
}