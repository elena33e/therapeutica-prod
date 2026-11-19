package com.therapeutica.therapeutica_app.chestionare;

import com.therapeutica.therapeutica_app.raport_chestionar.RezultatScoring;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.therapeutica.therapeutica_app.categorii_chestionare.CategoriiChestionareRepository;
import com.therapeutica.therapeutica_app.raport_chestionar.ScoringService;
import com.therapeutica.therapeutica_app.raspunsuri_chestionare.RaspunsuriChestionareRepository;
import jakarta.persistence.EntityManager;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockingDetails;


@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-http-test.yml"
})
@AutoConfigureMockMvc
@ActiveProfiles("http-test")
@Testcontainers
class RapoarteChestionareHttpTest {

    private static final String PAROLA = "Parola-fictiva-Test-123!";

    private static final Path DOCUMENTE = Path.of(
            System.getProperty("java.io.tmpdir"),
            "therapeutica-rapoarte-test-" + UUID.randomUUID()
    ).toAbsolutePath();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.1")
                    .withDatabaseName("therapeutica_rapoarte_test")
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

    @MockitoBean
    private JavaMailSender mailSender;

    @Autowired
    private ScoringService scoringService;

    @Autowired
    private EntityManager entityManager;

    @MockitoSpyBean
    private RaspunsuriChestionareRepository chestionareSpy;

    @MockitoSpyBean
    private CategoriiChestionareRepository categoriiSpy;

    private final Map<String, UUID> conturi = Map.of(
            "admin", UUID.randomUUID(),
            "medic-a", UUID.randomUUID(),
            "medic-b", UUID.randomUUID(),
            "pacient-a", UUID.randomUUID(),
            "pacient-b", UUID.randomUUID()
    );

    private final UUID pacientA = UUID.randomUUID();
    private final UUID pacientB = UUID.randomUUID();
    private final UUID sablon = UUID.randomUUID();
    private final UUID categorie = UUID.randomUUID();
    private final UUID categorieInfo = UUID.randomUUID();
    private final UUID intrebare = UUID.randomUUID();
    private final UUID intrebareInfo = UUID.randomUUID();

    private final UUID completat = UUID.randomUUID();
    private final UUID revizuit = UUID.randomUUID();
    private final UUID inLucru = UUID.randomUUID();
    private final UUID strain = UUID.randomUUID();

    @BeforeEach
    void pregatesteDatele() {
        assertThat(jdbc.queryForObject(
                "SELECT current_database()", String.class
        )).isEqualTo(postgres.getDatabaseName());

        jdbc.execute("TRUNCATE TABLE public.utilizatori CASCADE");

        String hash = encoder.encode(PAROLA);

        for (var cont : conturi.entrySet()) {
            jdbc.update("""
                    INSERT INTO public.utilizatori
                        (id, created_at, email, nume, prenume, rol, parola)
                    VALUES (?, CURRENT_TIMESTAMP, ?, 'Test', ?, ?, ?)
                    """,
                    cont.getValue(),
                    email(cont.getKey()),
                    cont.getKey(),
                    rol(cont.getKey()),
                    hash);
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
                pacientA, id("pacient-a"), id("medic-a"),
                pacientB, id("pacient-b"), id("medic-b"));

        jdbc.update("""
                INSERT INTO public.chestionare (id, nume)
                VALUES (?, 'Chestionar pentru test')
                """, sablon);

        jdbc.update("""
                INSERT INTO public.categorii_chestionare
                    (id, chestionar_id, nume, ordine,
                     este_evaluabila, sex_tinta)
                VALUES (?, ?, 'Categorie evaluabila', 1, true, 'AMBELE'),
                       (?, ?, 'Context', 2, false, 'AMBELE')
                """, categorie, sablon, categorieInfo, sablon);

        jdbc.update("""
                INSERT INTO public.intrebari
                    (id, categorie_id, text_intrebare,
                     tip_intrebare, obligatorie, ordine)
                VALUES (?, ?, 'Simptom privat din raport',
                        'SCOR_0_3', true, 1),
                       (?, ?, 'Context privat din raport',
                        'TEXT_LIBER', true, 1)
                """,
                intrebare, categorie,
                intrebareInfo, categorieInfo);

        atribuire(completat, pacientA, "medic-a", "COMPLETAT", 3);
        atribuire(revizuit, pacientA, "medic-a", "REVIZUIT", 0);
        atribuire(inLucru, pacientA, "medic-a", "NECOMPLETAT", null);
        atribuire(strain, pacientB, "medic-b", "COMPLETAT", 1);
    }

    @Test
    void fiecareMedicVedeNumaiRapoartelePacientilorSai()
            throws Exception {

        mvc.perform(get("/medic/rapoarte/{id}", id("medic-a"))
                        .session(login("medic-a")))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/rapoarte"))
                .andExpect(model().attribute("rapoarte", hasSize(2)))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, "no-store"
                ))
                .andExpect(content().string(containsString(url(completat))))
                .andExpect(content().string(containsString(url(revizuit))))
                .andExpect(content().string(not(containsString(url(inLucru)))))
                .andExpect(content().string(not(containsString(url(strain)))));

        MockHttpSession altMedic = login("medic-b");

        mvc.perform(get("/medic/rapoarte/{id}", id("medic-b"))
                        .session(altMedic))
                .andExpect(status().isOk())
                .andExpect(model().attribute("rapoarte", hasSize(1)))
                .andExpect(content().string(containsString(url(strain))))
                .andExpect(content().string(not(containsString(url(completat)))));

        mvc.perform(get("/medic/rapoarte/{id}", id("medic-a"))
                        .session(altMedic))
                .andExpect(status().isForbidden());

        assertThat(numarScoruri()).isZero();
    }

    @ParameterizedTest
    @CsvSource({"COMPLETAT,3", "REVIZUIT,0"})
    void raportulAfiseazaRaspunsurileSiPastreazaScorulExistent(
            String stare,
            int scor) throws Exception {

        UUID atribuireId = "COMPLETAT".equals(stare)
                ? completat
                : revizuit;

        // Scorul există înainte de deschiderea raportului.
        scoringService.calculeazaSiSalveazaScor(atribuireId);

        Map<String, Object> scorInitial = scorSalvat(atribuireId);
        MockHttpSession sesiune = login("medic-a");

        var rezultatHttp = mvc.perform(get(url(atribuireId))
                        .session(sesiune))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/vizualizare-rezultate"))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL, "no-store"
                ))
                .andExpect(model().attributeExists(
                        "chestionar", "rezultat",
                        "raspunsuriPeCategorii", "categoriiMap"
                ))
                .andExpect(content().string(
                        containsString("Simptom privat din raport")
                ))
                .andExpect(content().string(
                        containsString("Text privat al pacientului")
                ))
                .andExpect(content().string(
                        containsString("/css/raport-chestionar.css")
                ))
                .andExpect(content().string(
                        containsString("id=\"salveaza-pdf\"")
                ))
                .andReturn();

        RezultatScoring rezultat = (RezultatScoring)
                rezultatHttp.getModelAndView()
                        .getModel().get("rezultat");

        assertThat(rezultat.getScorTotal())
                .isEqualByComparingTo(BigDecimal.valueOf(scor));

        assertThat(scorSalvat(atribuireId)).isEqualTo(scorInitial);

        mvc.perform(get(url(atribuireId)).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/vizualizare-rezultate"));

        assertThat(scorSalvat(atribuireId)).isEqualTo(scorInitial);
        assertThat(numarScoruri()).isEqualTo(1L);
        assertThat(statusAtribuire(atribuireId)).isEqualTo(stare);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "medic-b", "pacient-a", "pacient-b", "admin"
    })
    void celelalteConturiNuPotCitiSauRevizuiRaportul(String cont)
            throws Exception {

        MockHttpSession sesiune = login(cont);

        mvc.perform(get(url(completat)).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(post(revizuireUrl(completat))
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(statusAtribuire(completat)).isEqualTo("COMPLETAT");
        assertThat(numarScoruri()).isZero();
        assertThat(numarAuditModificare(completat)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NECOMPLETAT", "IN_PROGRESS"})
    void unChestionarNefinalizatNuAreRaportSiNuPoateFiRevizuit(
            String stare) throws Exception {

        jdbc.update("""
                UPDATE public.raspunsuri_chestionare
                SET status = ? WHERE id = ?
                """, stare, inLucru);

        MockHttpSession sesiune = login("medic-a");

        mvc.perform(get(url(inLucru)).session(sesiune))
                .andExpect(status().isConflict());

        mvc.perform(post(revizuireUrl(inLucru))
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isConflict());

        assertThat(statusAtribuire(inLucru)).isEqualTo(stare);
        assertThat(numarScoruri()).isZero();
        assertThat(numarAuditModificare(inLucru)).isZero();
    }

    @Test
    void revizuireaFolosestePacientulRealSiNuSeRepetaInAudit()
            throws Exception {

        // Scenariul pornește de la un raport deja pregătit.
        scoringService.calculeazaSiSalveazaScor(completat);

        Map<String, Object> scorInitial = scorSalvat(completat);
        MockHttpSession sesiune = login("medic-a");

        mvc.perform(get(url(completat)).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/vizualizare-rezultate"));

        for (int i = 0; i < 2; i++) {
            mvc.perform(post(revizuireUrl(completat))
                            .session(sesiune)
                            .with(csrf())
                            .param("pacientId", pacientB.toString()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(
                            "/medic/" + id("medic-a")
                                    + "/pacient/" + pacientA
                    ));
        }

        assertThat(statusAtribuire(completat)).isEqualTo("REVIZUIT");
        assertThat(statusAtribuire(strain)).isEqualTo("COMPLETAT");
        assertThat(scorSalvat(completat)).isEqualTo(scorInitial);
        assertThat(numarAuditModificare(completat)).isEqualTo(1L);

        assertThat(jdbc.queryForObject("""
            SELECT pacient_id FROM public.audit_log
            WHERE resursa_id = ?
              AND tip_resursa = 'CHESTIONAR'
              AND actiune = 'MODIFICARE'
            """, UUID.class, completat))
                .isEqualTo(pacientA);
    }

    @Test
    void revizuireaNecesitaPostSiTokenCsrfValid()
            throws Exception {

        MockHttpSession sesiune = login("medic-a");

        mvc.perform(get(revizuireUrl(completat)).session(sesiune))
                .andExpect(status().isMethodNotAllowed());

        mvc.perform(post(revizuireUrl(completat)).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(post(revizuireUrl(completat))
                        .session(sesiune)
                        .with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());

        assertThat(statusAtribuire(completat)).isEqualTo("COMPLETAT");
        assertThat(numarAuditModificare(completat)).isZero();
    }

    @Test
    void istoriculPacientuluiNuExpuneRaportulSauRaspunsurile()
            throws Exception {

        mvc.perform(get(
                        "/chestionare/pacient/{id}/istoric",
                        id("pacient-a")
                ).session(login("pacient-a")))
                .andExpect(status().isOk())
                .andExpect(model().attribute(
                        "istoricChestionare", hasSize(2)
                ))
                .andExpect(content().string(
                        not(containsString("Simptom privat din raport"))
                ))
                .andExpect(content().string(
                        not(containsString("Text privat al pacientului"))
                ))
                .andExpect(content().string(
                        not(containsString("/chestionare/medic/vizualizare/"))
                ))
                .andExpect(content().string(
                        not(containsString("Index de Severitate Globală"))
                ));
    }

    @Test
    void accesulUrmeazaMediculAsociatInPrezentPacientului()
            throws Exception {

        jdbc.update("""
                UPDATE public.pacienti
                SET medic_id = ? WHERE id = ?
                """, id("medic-b"), pacientA);

        MockHttpSession fostMedic = login("medic-a");

        mvc.perform(get(url(completat)).session(fostMedic))
                .andExpect(status().isForbidden());

        mvc.perform(get("/medic/rapoarte/{id}", id("medic-a"))
                        .session(fostMedic))
                .andExpect(status().isOk())
                .andExpect(model().attribute("rapoarte", hasSize(0)));

        MockHttpSession medicActual = login("medic-b");

        mvc.perform(get("/medic/rapoarte/{id}", id("medic-b"))
                        .session(medicActual))
                .andExpect(status().isOk())
                .andExpect(model().attribute("rapoarte", hasSize(3)));

        mvc.perform(get(url(completat)).session(medicActual))
                .andExpect(status().isOk());
    }

    @Test
    void oAtribuireInexistentaReturneaza404() throws Exception {
        MockHttpSession sesiune = login("medic-a");
        UUID inexistent = UUID.randomUUID();

        mvc.perform(get(url(inexistent)).session(sesiune))
                .andExpect(status().isNotFound());

        mvc.perform(post(revizuireUrl(inexistent))
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isNotFound());

        assertThat(numarScoruri()).isZero();
    }

    @Test
    void vizitatorulEsteTrimisLaLogin() throws Exception {
        for (String cale : List.of(
                url(completat),
                "/medic/rapoarte/" + id("medic-a")
        )) {
            mvc.perform(get(cale))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost/login"));
        }

        mvc.perform(post(revizuireUrl(completat)).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));

        assertThat(statusAtribuire(completat)).isEqualTo("COMPLETAT");
        assertThat(numarScoruri()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"medic-a", "pacient-a", "admin"})
    void ruteleVechiSuntRefuzate(String cont) throws Exception {
        MockHttpSession sesiune = login(cont);

        for (String cale : List.of(
                "/chestionare/raport/visual/" + completat,
                "/raspunsuri-chestionare/" + completat + "/detalii",
                "/raspunsuri-chestionare/pacient/"
                        + id("pacient-a") + "/istoric",
                "/medic/" + id("medic-a") + "/pacienti-neevaluati",
                "/medic/" + id("medic-a")
                        + "/pacient/" + pacientA + "/chestionare",
                "/medic/" + id("medic-a")
                        + "/chestionar/" + completat + "/rezultate"
        )) {
            mvc.perform(get(cale).session(sesiune))
                    .andExpect(status().isForbidden());
        }

        assertThat(numarScoruri()).isZero();
    }

    @Test
    void stilurileRaportuluiSuntDisponibileLaAdresaFolosita()
            throws Exception {

        mvc.perform(get("/css/raport-chestionar.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("@media print")))
                .andExpect(content().string(
                        containsString(".report-chart-image")
                ));
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
                .andReturn();

        MockHttpSession sesiune =
                (MockHttpSession) rezultat.getRequest().getSession(false);

        assertThat(sesiune).isNotNull();
        return sesiune;
    }

    private void atribuire(
            UUID atribuireId,
            UUID pacientId,
            String medic,
            String stare,
            Integer scor) {

        jdbc.update("""
                INSERT INTO public.raspunsuri_chestionare
                    (id, pacient_id, medic_id, chestionar_id,
                     status, completat_la)
                VALUES (?, ?, ?, ?, ?,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END)
                """,
                atribuireId, pacientId, id(medic), sablon, stare,
                scor != null);

        if (scor == null) {
            return;
        }

        jdbc.update("""
                INSERT INTO public.raspunsuri_intrebari
                    (id, raspuns_chestionar_id,
                     intrebare_id, categorie_id, scor)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), atribuireId,
                intrebare, categorie, scor);

        jdbc.update("""
                INSERT INTO public.raspunsuri_intrebari
                    (id, raspuns_chestionar_id,
                     intrebare_id, categorie_id, raspuns_text)
                VALUES (?, ?, ?, ?, 'Text privat al pacientului')
                """,
                UUID.randomUUID(), atribuireId,
                intrebareInfo, categorieInfo);
    }

    private Map<String, Object> scorSalvat(UUID atribuireId) {
        return jdbc.queryForMap("""
                SELECT id, scor_total, calculat_la
                FROM public.rezultate_scoring
                WHERE raspuns_chestionar_id = ?
                """, atribuireId);
    }

    private String statusAtribuire(UUID atribuireId) {
        return jdbc.queryForObject("""
                SELECT status FROM public.raspunsuri_chestionare
                WHERE id = ?
                """, String.class, atribuireId);
    }

    private long numarScoruri() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.rezultate_scoring",
                Long.class
        );
    }

    private long numarAuditModificare(UUID atribuireId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.audit_log
                WHERE resursa_id = ?
                  AND tip_resursa = 'CHESTIONAR'
                  AND actiune = 'MODIFICARE'
                """, Long.class, atribuireId);
    }

    private UUID id(String cont) {
        return conturi.get(cont);
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

    private String url(UUID atribuireId) {
        return "/chestionare/medic/vizualizare/" + atribuireId;
    }

    private String revizuireUrl(UUID atribuireId) {
        return "/chestionare/medic/marcheaza-revizuit/" + atribuireId;
    }

    @Test
    void deschidereaUnuiRaportNepregatitNuCreeazaScoruri()
            throws Exception {

        mvc.perform(get(url(completat)).session(login("medic-a")))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/pregatire-raport"))
                .andExpect(model().attribute(
                        "raspunsChestionarId", completat
                ))
                .andExpect(content().string(
                        containsString(pregatireUrl(completat))
                ));

        assertThat(numarScoruri()).isZero();
        assertThat(statusAtribuire(completat)).isEqualTo("COMPLETAT");
    }

    @ParameterizedTest
    @ValueSource(strings = {"COMPLETAT", "REVIZUIT"})
    void pregatireaPrinPostEsteIdempotenta(String stare)
            throws Exception {

        UUID atribuireId = "COMPLETAT".equals(stare)
                ? completat
                : revizuit;

        MockHttpSession sesiune = login("medic-a");

        for (int i = 0; i < 2; i++) {
            mvc.perform(post(pregatireUrl(atribuireId))
                            .session(sesiune)
                            .with(csrf()))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(url(atribuireId)));
        }

        assertThat(numarScoruri()).isEqualTo(1L);
        assertThat(numarAuditModificare(atribuireId)).isEqualTo(1L);
        assertThat(statusAtribuire(atribuireId)).isEqualTo(stare);

        mvc.perform(get(url(atribuireId)).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/vizualizare-rezultate"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "null"})
    void unScorNevalidNuEsteRecalculatPrinGet(String valoare)
            throws Exception {

        scoringService.calculeazaSiSalveazaScor(completat);

        Boolean necesitaRecalculare = "null".equals(valoare)
                ? null
                : Boolean.TRUE;

        jdbc.update("""
            UPDATE public.rezultate_scoring
            SET necesita_recalculare = ?
            WHERE raspuns_chestionar_id = ?
            """, necesitaRecalculare, completat);

        Map<String, Object> scorInitial = scorSalvat(completat);
        MockHttpSession sesiune = login("medic-a");

        mvc.perform(get(url(completat)).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/pregatire-raport"));

        assertThat(scorSalvat(completat)).isEqualTo(scorInitial);

        assertThat(jdbc.queryForObject("""
            SELECT necesita_recalculare
            FROM public.rezultate_scoring
            WHERE raspuns_chestionar_id = ?
            """, Boolean.class, completat))
                .isEqualTo(necesitaRecalculare);

        mvc.perform(post(pregatireUrl(completat))
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(url(completat)));

        assertThat(numarScoruri()).isEqualTo(1L);

        assertThat(jdbc.queryForObject("""
            SELECT necesita_recalculare
            FROM public.rezultate_scoring
            WHERE raspuns_chestionar_id = ?
            """, Boolean.class, completat))
                .isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "medic-b", "pacient-a", "pacient-b", "admin"
    })
    void altContNuPoatePregatiRaportul(String cont) throws Exception {
        mvc.perform(post(pregatireUrl(completat))
                        .session(login(cont))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(numarScoruri()).isZero();
        assertThat(numarAuditModificare(completat)).isZero();
    }

    @Test
    void pregatireaNecesitaCsrf() throws Exception {
        MockHttpSession sesiune = login("medic-a");

        mvc.perform(post(pregatireUrl(completat)).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(post(pregatireUrl(completat))
                        .session(sesiune)
                        .with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());

        assertThat(numarScoruri()).isZero();
    }

    @Test
    void unChestionarFaraRaspunsuriNuPrimesteUnScorArtificial()
            throws Exception {

        jdbc.update("""
            DELETE FROM public.raspunsuri_intrebari
            WHERE raspuns_chestionar_id = ?
            """, completat);

        mvc.perform(post(pregatireUrl(completat))
                        .session(login("medic-a"))
                        .with(csrf()))
                .andExpect(status().isConflict());

        assertThat(numarScoruri()).isZero();
    }

    @Test
    void revizuireaNecesitaUnRaportPregatit() throws Exception {
        mvc.perform(post(revizuireUrl(completat))
                        .session(login("medic-a"))
                        .with(csrf()))
                .andExpect(status().isConflict());

        assertThat(statusAtribuire(completat)).isEqualTo("COMPLETAT");
        assertThat(numarAuditModificare(completat)).isZero();
    }

    @Test
    void schimbareaRoluluiInvalideazaAccesulSesiuniiExistente()
            throws Exception {

        MockHttpSession sesiune = login("medic-a");

        jdbc.update("""
            UPDATE public.utilizatori
            SET rol = 'PACIENT' WHERE id = ?
            """, id("medic-a"));

        mvc.perform(get(url(completat)).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(get("/medic/rapoarte/{id}", id("medic-a"))
                        .session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(post(pregatireUrl(completat))
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mvc.perform(post(revizuireUrl(completat))
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(numarScoruri()).isZero();
        assertThat(statusAtribuire(completat)).isEqualTo("COMPLETAT");
    }

    @Test
    void schimbareaRoluluiPacientuluiInvalideazaAccesulLaCompletare()
            throws Exception {

        MockHttpSession sesiune = login("pacient-a");

        jdbc.update("""
            UPDATE public.utilizatori
            SET rol = 'MEDIC' WHERE id = ?
            """, id("pacient-a"));

        mvc.perform(get(
                        "/chestionare/completare/{id}", inLucru
                ).session(sesiune))
                .andExpect(status().isForbidden());

        assertThat(statusAtribuire(inLucru)).isEqualTo("NECOMPLETAT");
    }

    @Test
    void identitateaDinSesiuneNuPoateInlocuiIdentitateaAutentificata()
            throws Exception {

        MockHttpSession sesiune = login("medic-a");
        sesiune.setAttribute("userId", id("medic-b").toString());

        mvc.perform(post(pregatireUrl(completat))
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(url(completat)));

        mvc.perform(post(pregatireUrl(strain))
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("""
            SELECT utilizator_id FROM public.audit_log
            WHERE resursa_id = ?
              AND tip_resursa = 'CHESTIONAR'
              AND actiune = 'MODIFICARE'
            """, UUID.class, completat))
                .isEqualTo(id("medic-a"));

        mvc.perform(post(revizuireUrl(completat))
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/medic/" + id("medic-a")
                                + "/pacient/" + pacientA
                ));
    }

    @Test
    void eroareaLaAfisareaFormularuluiNuExpuneDetaliiInterne()
            throws Exception {

        doThrow(new IllegalStateException(
                "DETALIU_SQL_SECRET"
        )).when(categoriiSpy).findByChestionarId(sablon);

        mvc.perform(get(
                        "/chestionare/completare/{id}", inLucru
                ).session(login("pacient-a")))
                .andExpect(status().isInternalServerError())
                .andExpect(view().name("error"))
                .andExpect(model().attribute(
                        "error",
                        "Chestionarul nu poate fi afișat momentan. Încearcă din nou."
                ))
                .andExpect(content().string(
                        not(containsString("DETALIU_SQL_SECRET"))
                ));
    }

    @Test
    void douaPregatiriSimultaneCreeazaUnSingurScor()
            throws Exception {

        MockHttpSession primaSesiune = login("medic-a");
        MockHttpSession aDouaSesiune = login("medic-a");

        CountDownLatch primaAreBlocarea = new CountDownLatch(1);
        CountDownLatch permiteContinuarea = new CountDownLatch(1);
        CountDownLatch aDouaIncearcaBlocarea = new CountDownLatch(1);

        AtomicInteger incercari = new AtomicInteger();
        AtomicInteger primulPid = new AtomicInteger();
        AtomicInteger alDoileaPid = new AtomicInteger();

        var executieReala = mockingDetails(chestionareSpy)
                .getMockCreationSettings()
                .getDefaultAnswer();

        doAnswer(invocare -> {
            int incercare = incercari.incrementAndGet();

            entityManager.createNativeQuery(
                    "SET LOCAL lock_timeout = '30s'"
            ).executeUpdate();

            int pid = ((Number) entityManager
                    .createNativeQuery("SELECT pg_backend_pid()")
                    .getSingleResult())
                    .intValue();

            if (incercare == 1) {
                primulPid.set(pid);

                Object rezultat = executieReala.answer(invocare);
                primaAreBlocarea.countDown();

                if (!permiteContinuarea.await(45, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "Prima pregătire nu a fost eliberată de test."
                    );
                }

                return rezultat;
            }

            if (incercare == 2) {
                alDoileaPid.set(pid);
                aDouaIncearcaBlocarea.countDown();
            }

            return executieReala.answer(invocare);
        }).when(chestionareSpy).findByIdForUpdate(completat);

        var executii = Executors.newFixedThreadPool(2);

        try {
            var prima = executii.submit(() ->
                    trimitePregatire(primaSesiune)
            );

            assertThat(primaAreBlocarea.await(15, TimeUnit.SECONDS))
                    .isTrue();

            var aDoua = executii.submit(() ->
                    trimitePregatire(aDouaSesiune)
            );

            assertThat(aDouaIncearcaBlocarea.await(15, TimeUnit.SECONDS))
                    .isTrue();

            assertThat(primulPid.get()).isPositive();
            assertThat(alDoileaPid.get())
                    .isPositive()
                    .isNotEqualTo(primulPid.get());

            boolean blocareConfirmata = false;
            long termen = System.nanoTime()
                    + TimeUnit.SECONDS.toNanos(8);

            while (System.nanoTime() < termen) {
                Boolean blocata = jdbc.queryForObject(
                        "SELECT ? = ANY(pg_blocking_pids(?))",
                        Boolean.class,
                        primulPid.get(),
                        alDoileaPid.get()
                );

                if (Boolean.TRUE.equals(blocata)) {
                    blocareConfirmata = true;
                    break;
                }

                if (aDoua.isDone()) {
                    break;
                }

                Thread.sleep(25);
            }

            assertThat(blocareConfirmata)
                    .as("A doua cerere așteaptă blocarea reală din PostgreSQL")
                    .isTrue();

            permiteContinuarea.countDown();

            for (var rezultat : List.of(
                    prima.get(20, TimeUnit.SECONDS),
                    aDoua.get(20, TimeUnit.SECONDS)
            )) {
                status().is3xxRedirection().match(rezultat);
                redirectedUrl(url(completat)).match(rezultat);
            }

            assertThat(incercari.get()).isEqualTo(2);
        } finally {
            permiteContinuarea.countDown();
            executii.shutdownNow();

            assertThat(executii.awaitTermination(20, TimeUnit.SECONDS))
                    .isTrue();
        }

        assertThat(numarScoruri()).isEqualTo(1L);
        assertThat(numarAuditModificare(completat)).isEqualTo(1L);
        assertThat(statusAtribuire(completat)).isEqualTo("COMPLETAT");
    }

    private MvcResult trimitePregatire(MockHttpSession sesiune)
            throws Exception {

        return mvc.perform(post(pregatireUrl(completat))
                        .session(sesiune)
                        .with(csrf()))
                .andReturn();
    }

    private String pregatireUrl(UUID atribuireId) {
        return "/chestionare/medic/pregateste-raport/" + atribuireId;
    }

    @Test
    void resurseleCompilateSuntDisponibileLocal() throws Exception {
        for (String cale : List.of(
                "/css/bundle.css",
                "/js/bundle.js",
                "/js/raport.js"
        )) {
            mvc.perform(get(cale))
                    .andExpect(status().isOk());
        }

        mvc.perform(get("/css/bundle.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("--color-brand-navy")
                ));
    }

    @Test
    void paginileNuIncarcaScripturiSauStiluriExterne()
            throws Exception {

        scoringService.calculeazaSiSalveazaScor(completat);

        String loginHtml = mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String raportHtml = mvc.perform(get(url(completat))
                        .session(login("medic-a")))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/vizualizare-rezultate"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        var resursaExterna = java.util.regex.Pattern.compile(
                "<(?:script|link)\\b[^>]*\\b(?:src|href)\\s*=\\s*[\"'](?:https?:)?//",
                java.util.regex.Pattern.CASE_INSENSITIVE
        );

        for (String html : List.of(loginHtml, raportHtml)) {
            assertThat(resursaExterna.matcher(html).find())
                    .as("Scripturile și stilurile sunt încărcate local")
                    .isFalse();
        }
    }
}