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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-http-test.yml"
})
@AutoConfigureMockMvc
@ActiveProfiles("http-test")
@Testcontainers
class ApiRaspunsuriChestionareRetrasHttpTest {

    private static final String API = "/api/raspunsuri-chestionare";

    private static final String ID_TEST =
            "00000000-0000-0000-0000-000000000001";

    private record CerereRetrasa(HttpMethod metoda, String cale) {}

    private static final List<CerereRetrasa> CERERI_RETRASE = List.of(
            new CerereRetrasa(HttpMethod.GET, API),
            new CerereRetrasa(HttpMethod.GET, API + "/" + ID_TEST),
            new CerereRetrasa(
                    HttpMethod.GET,
                    API + "/pacient/" + ID_TEST
            ),
            new CerereRetrasa(
                    HttpMethod.GET,
                    API + "/medic/" + ID_TEST
            ),
            new CerereRetrasa(
                    HttpMethod.GET,
                    API + "/chestionar/" + ID_TEST
            ),
            new CerereRetrasa(HttpMethod.POST, API),
            new CerereRetrasa(HttpMethod.PUT, API + "/" + ID_TEST),
            new CerereRetrasa(HttpMethod.DELETE, API + "/" + ID_TEST)
    );

    private static final Path DIRECTOR_DOCUMENTE = Path.of(
            System.getProperty("java.io.tmpdir"),
            "therapeutica-api-retras-test-" + UUID.randomUUID()
    ).toAbsolutePath();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.1")
                    .withDatabaseName("therapeutica_api_retras_test")
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
    void aplicatiaNuMaiInregistreazaRuteleApiRetrase() {
        var ruteInregistrate = requestMappingHandlerMapping
                .getHandlerMethods()
                .keySet()
                .stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .toList();

        assertThat(ruteInregistrate)
                .noneMatch(ruta ->
                        ruta.equals(API)
                                || ruta.startsWith(API + "/")
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {"PACIENT", "MEDIC"})
    void toateOperatiileSuntRefuzateUtilizatorilorAutentificati(
            String rol) throws Exception {

        // Identitatea simulată verifică refuzul în filtrul de securitate.
        for (CerereRetrasa cerere : CERERI_RETRASE) {
            mockMvc.perform(
                            construiesteCererea(cerere)
                                    .with(user("utilizator-test").roles(rol))
                    )
                    .andExpect(status().isForbidden())
                    .andExpect(rezultat ->
                            assertThat(rezultat.getHandler()).isNull()
                    );
        }
    }

    @Test
    void vizitatorulNuPoateAccesaOperatiileApiRetrase()
            throws Exception {

        for (CerereRetrasa cerere : CERERI_RETRASE) {
            mockMvc.perform(construiesteCererea(cerere))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost/login"))
                    .andExpect(rezultat ->
                            assertThat(rezultat.getHandler()).isNull()
                    );
        }
    }

    private MockHttpServletRequestBuilder construiesteCererea(
            CerereRetrasa cerere) {

        var cerereHttp = request(cerere.metoda(), cerere.cale())
                .with(csrf())
                .accept(MediaType.TEXT_HTML);

        if (HttpMethod.POST.equals(cerere.metoda())
                || HttpMethod.PUT.equals(cerere.metoda())) {

            cerereHttp
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {
                              "status": "COMPLETAT",
                              "scorTotalGeneral": 3.00
                            }
                            """);
        }

        return cerereHttp;
    }
}