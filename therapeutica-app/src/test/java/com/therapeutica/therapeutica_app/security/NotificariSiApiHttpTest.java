package com.therapeutica.therapeutica_app.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-http-test.yml"
})
@AutoConfigureMockMvc
@ActiveProfiles("http-test")
@Testcontainers
class NotificariSiApiHttpTest {

    private static final String PAROLA = "Parola-fictiva-Test-123!";

    private static final List<String> CONTURI = List.of(
            "admin",
            "medic-a",
            "medic-b",
            "pacient-a",
            "pacient-b"
    );

    private static final List<String> API_RETRASE = List.of(
            "/api/atribuire-chestionare",
            "/api/chestionare",
            "/api/categorii-chestionare",
            "/api/intrebari"
    );

    private static final Path DOCUMENTE = Path.of(
            System.getProperty("java.io.tmpdir"),
            "therapeutica-notificari-test-" + UUID.randomUUID()
    ).toAbsolutePath();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.1")
                    .withDatabaseName("therapeutica_notificari_test")
                    .withUsername("test_user")
                    .withPassword("test_password");

    @DynamicPropertySource
    static void configureazaStocarea(DynamicPropertyRegistry registry) {
        registry.add("app.storage.documents-dir", DOCUMENTE::toString);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @MockitoBean
    private JavaMailSender mailSender;

    private final Map<String, UUID> utilizatori = new HashMap<>();
    private final Map<String, Long> notificari = new HashMap<>();

    @BeforeEach
    void pregatesteDatele() {
        // Nu permitem curățarea unei alte baze.
        assertThat(jdbc.queryForObject(
                "SELECT current_database()", String.class
        )).isEqualTo(postgres.getDatabaseName());

        jdbc.execute("TRUNCATE TABLE public.utilizatori CASCADE");

        String hash = encoder.encode(PAROLA);

        for (String cont : CONTURI) {
            UUID id = UUID.randomUUID();
            utilizatori.put(cont, id);

            jdbc.update("""
                    INSERT INTO public.utilizatori
                        (id, created_at, email, nume, prenume, rol, parola)
                    VALUES (?, CURRENT_TIMESTAMP, ?, 'Test', 'Cont', ?, ?)
                    """, id, email(cont), rol(cont), hash);
        }

        jdbc.update("""
                INSERT INTO public.medici (user_id, specializare)
                VALUES (?, 'Test'), (?, 'Test')
                """, id("medic-a"), id("medic-b"));

        jdbc.update("""
                INSERT INTO public.pacienti (id, user_id, medic_id, sex)
                VALUES (?, ?, ?, 'NEPRECIZAT'),
                       (?, ?, ?, 'NEPRECIZAT')
                """,
                UUID.randomUUID(), id("pacient-a"), id("medic-a"),
                UUID.randomUUID(), id("pacient-b"), id("medic-a"));

        for (String cont : CONTURI) {
            if ("admin".equals(cont)) {
                continue;
            }

            Long notificareId = jdbc.queryForObject("""
                    INSERT INTO public.notificari
                        (citita, data_creare, link_actiune,
                         mesaj, titlu, utilizator_destinatar_id)
                    VALUES
                        (false, CURRENT_TIMESTAMP, ?, ?, ?, ?)
                    RETURNING id
                    """,
                    Long.class,
                    dashboard(cont),
                    "Mesaj destinat " + cont,
                    "Notificare " + cont,
                    id(cont));

            notificari.put(cont, notificareId);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"medic-a", "pacient-a"})
    void proprietarulVedeSiAcceseazaDoarNotificarileProprii(String cont)
            throws Exception {

        MockHttpSession sesiune = login(cont);
        long notificareId = notificari.get(cont);

        mvc.perform(get(inbox(cont)).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(model().attribute("notificari", hasSize(1)))
                .andExpect(content().string(
                        containsString("Mesaj destinat " + cont)
                ))
                .andExpect(content().string(
                        not(containsString("Mesaj destinat medic-b"))
                ))
                .andExpect(content().string(
                        not(containsString("Mesaj destinat pacient-b"))
                ))
                .andExpect(content().string(containsString(
                        "action=\"/api/notificari/acceseaza/"
                                + notificareId + "\""
                )))
                .andExpect(content().string(
                        containsString("name=\"_csrf\"")
                ));

        mvc.perform(get(countUrl(cont)).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(content().string("1"));

        // Deschiderea inbox-ului și numărarea nu marchează citirea.
        assertThat(citita(cont)).isFalse();

        // Retrimiterea este permisă și nu creează alte notificări.
        for (int i = 0; i < 2; i++) {
            mvc.perform(post(
                            "/api/notificari/acceseaza/{id}",
                            notificareId
                    )
                            .session(sesiune)
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(dashboard(cont)));
        }

        assertThat(citita(cont)).isTrue();

        mvc.perform(get(countUrl(cont)).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(content().string("0"));

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.notificari",
                Long.class
        )).isEqualTo(4L);
    }

    @ParameterizedTest
    @CsvSource({
            "medic-a,medic-b",
            "medic-a,pacient-a",
            "pacient-a,pacient-b",
            "pacient-a,medic-a",
            "admin,medic-a",
            "admin,pacient-a"
    })
    void altContNuPoateCitiNumaraSauModificaNotificarile(
            String solicitant,
            String proprietar) throws Exception {

        MockHttpSession sesiune = login(solicitant);

        mvc.perform(get(inbox(proprietar)).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(get(countUrl(proprietar)).session(sesiune))
                .andExpect(status().isForbidden());

        int statusAsteptat = "admin".equals(solicitant) ? 403 : 404;

        mvc.perform(post(
                        "/api/notificari/acceseaza/{id}",
                        notificari.get(proprietar)
                )
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().is(statusAsteptat));

        assertThat(citita(proprietar)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"medic-a", "pacient-a"})
    void AccesareaNecesitaPostSiCsrf(String cont) throws Exception {
        MockHttpSession sesiune = login(cont);
        long notificareId = notificari.get(cont);

        mvc.perform(get(
                        "/api/notificari/acceseaza/{id}",
                        notificareId
                ).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(post(
                        "/api/notificari/acceseaza/{id}",
                        notificareId
                ).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(post(
                        "/api/notificari/acceseaza/{id}",
                        notificareId
                )
                        .session(sesiune)
                        .with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());

        assertThat(citita(cont)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"medic-a", "pacient-a"})
    void notificareaInexistentaReturneaza404(String cont)
            throws Exception {

        mvc.perform(post(
                        "/api/notificari/acceseaza/{id}",
                        Long.MAX_VALUE
                )
                        .session(login(cont))
                        .with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(citita(cont)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"medic-a", "pacient-a"})
    void unLinkExternRevineLaInboxulPropriu(String cont)
            throws Exception {

        jdbc.update("""
                UPDATE public.notificari
                SET link_actiune = ?
                WHERE id = ?
                """,
                "//example.test/destinatie",
                notificari.get(cont));

        mvc.perform(post(
                        "/api/notificari/acceseaza/{id}",
                        notificari.get(cont)
                )
                        .session(login(cont))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(inbox(cont)));

        assertThat(citita(cont)).isTrue();
    }

    @Test
    void schimbareaAtributuluiDinSesiuneNuSchimbaProprietarul()
            throws Exception {

        MockHttpSession sesiune = login("medic-a");
        sesiune.setAttribute("userId", id("medic-b").toString());

        mvc.perform(get(countUrl("medic-a")).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(content().string("1"));

        mvc.perform(get(countUrl("medic-b")).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(post(
                        "/api/notificari/acceseaza/{id}",
                        notificari.get("medic-b")
                )
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(citita("medic-b")).isFalse();
    }

    @Test
    void utilizatorulNeautentificatEsteTrimisLaLogin()
            throws Exception {

        for (String cont : List.of("medic-a", "pacient-a")) {
            mvc.perform(get(inbox(cont)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost/login"));

            mvc.perform(get(countUrl(cont)))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost/login"));

            mvc.perform(post(
                            "/api/notificari/acceseaza/{id}",
                            notificari.get(cont)
                    ).with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost/login"));

            assertThat(citita(cont)).isFalse();
        }
    }

    @Test
    void controllereleApiRetraseNuMaiAuRuteInregistrate() {
        for (var mapping : handlerMapping.getHandlerMethods().keySet()) {
            for (String pattern : mapping.getPatternValues()) {
                for (String prefix : API_RETRASE) {
                    assertThat(pattern).doesNotStartWith(prefix);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "medic-a", "pacient-a"})
    void apiurileRetraseSiNecunoscuteSuntRefuzate(String cont)
            throws Exception {

        MockHttpSession sesiune = login(cont);

        for (String prefix : API_RETRASE) {
            for (String cale : List.of(
                    prefix,
                    prefix + "/" + id("pacient-a")
            )) {
                for (HttpMethod metoda : List.of(
                        HttpMethod.GET,
                        HttpMethod.POST,
                        HttpMethod.PUT,
                        HttpMethod.PATCH,
                        HttpMethod.DELETE
                )) {
                    mvc.perform(request(metoda, cale)
                                    .session(sesiune)
                                    .with(csrf()))
                            .andExpect(status().isForbidden());
                }
            }
        }

        mvc.perform(get(
                        "/api/notificari/necitite/{id}",
                        id("medic-a")
                ).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(post(
                        "/api/notificari/marcheaza-citita/{id}",
                        notificari.get("medic-a")
                )
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/ruta-necunoscuta").session(sesiune))
                .andExpect(status().isForbidden());

        assertThat(citita("medic-a")).isFalse();
        assertThat(citita("pacient-a")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "medic-a", "pacient-a"})
    void deconectareaNecesitaCsrfSiFunctioneazaPentruToateRolurile(
            String cont) throws Exception {

        MockHttpSession sesiune = login(cont);

        mvc.perform(post("/api/auth/logout-web").session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/auth/logout-web")
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?logout=true"))
                .andExpect(unauthenticated());
    }

    private MockHttpSession login(String cont) throws Exception {
        var rezultat = mvc.perform(post("/process-login")
                        .with(csrf())
                        .param("username", email(cont))
                        .param("password", PAROLA))
                .andExpect(status().is3xxRedirection())
                .andExpect(authenticated()
                        .withUsername(email(cont))
                        .withRoles(rol(cont)))
                .andExpect(redirectedUrl(dashboard(cont)))
                .andReturn();

        MockHttpSession sesiune =
                (MockHttpSession) rezultat.getRequest().getSession(false);

        assertThat(sesiune).isNotNull();

        return sesiune;
    }

    private boolean citita(String cont) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT citita FROM public.notificari WHERE id = ?
                """, Boolean.class, notificari.get(cont)));
    }

    private UUID id(String cont) {
        return utilizatori.get(cont);
    }

    private String email(String cont) {
        return cont + "@example.test";
    }

    private String rol(String cont) {
        if ("admin".equals(cont)) {
            return "ADMIN";
        }

        return cont.startsWith("medic") ? "MEDIC" : "PACIENT";
    }

    private String prefix(String cont) {
        if ("admin".equals(cont)) {
            return "/admin";
        }

        return cont.startsWith("medic") ? "/medic" : "/pacient";
    }

    private String dashboard(String cont) {
        return prefix(cont) + "/dashboard/" + id(cont);
    }

    private String inbox(String cont) {
        return prefix(cont) + "/notificari/" + id(cont);
    }

    private String countUrl(String cont) {
        return "/api/notificari/necitite-count/" + id(cont);
    }
}