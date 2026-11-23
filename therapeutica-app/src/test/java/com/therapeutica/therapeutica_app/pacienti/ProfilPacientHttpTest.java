package com.therapeutica.therapeutica_app.pacienti;

import com.therapeutica.therapeutica_app.utilizatori.UtilizatoriRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.util.LinkedMultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-http-test.yml"
})
@AutoConfigureMockMvc
@ActiveProfiles("http-test")
@Testcontainers
class ProfilPacientHttpTest {

    private static final String PAROLA = "Parola-fictiva-Test-123!";
    private static final String EMAIL_PACIENT = "pacient@example.test";
    private static final String EMAIL_ALT = "alt-pacient@example.test";
    private static final String EMAIL_NOU = "nou@example.test";

    // Valori sintetice pentru testele automate.
    private static final String CNP_INITIAL = "1900101400012";
    private static final String CNP_ALT = "1900101400081";
    private static final String CNP_NOU = "1900101400020";

    private static final Path DOCUMENTE = Path.of(
            System.getProperty("java.io.tmpdir"),
            "therapeutica-profil-pacient-test-" + UUID.randomUUID()
    ).toAbsolutePath();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.1")
                    .withDatabaseName("therapeutica_profil_pacient_test")
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

    @PersistenceContext
    private EntityManager entityManager;

    @MockitoBean
    private JavaMailSender mailSender;

    @MockitoSpyBean
    private UtilizatoriRepository utilizatoriRepository;

    @MockitoSpyBean
    private PacientiRepository pacientiRepository;

    private final UUID pacientUserId = UUID.randomUUID();
    private final UUID pacientId = UUID.randomUUID();
    private final UUID altPacientUserId = UUID.randomUUID();
    private final UUID altPacientId = UUID.randomUUID();
    private final UUID medicId = UUID.randomUUID();
    private final UUID altMedicId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void pregatesteDatele() {
        // Curățarea este permisă exclusiv în baza temporară.
        assertThat(jdbc.queryForObject(
                "SELECT current_database()", String.class
        )).isEqualTo(postgres.getDatabaseName());

        jdbc.execute("TRUNCATE TABLE public.utilizatori CASCADE");

        String hash = encoder.encode(PAROLA);

        utilizator(pacientUserId, EMAIL_PACIENT, "PACIENT", hash);
        utilizator(altPacientUserId, EMAIL_ALT, "PACIENT", hash);
        utilizator(medicId, "medic@example.test", "MEDIC", hash);
        utilizator(altMedicId, "alt-medic@example.test", "MEDIC", hash);
        utilizator(adminId, "admin@example.test", "ADMIN", hash);

        jdbc.update("""
                INSERT INTO public.medici
                    (user_id, specializare, telefon)
                VALUES
                    (?, 'Medicina interna', '0700000000'),
                    (?, 'Neurologie', '0711111111')
                """, medicId, altMedicId);

        jdbc.update("""
                INSERT INTO public.pacienti (
                    id, user_id, medic_id, cnp, data_nasterii,
                    sex, inaltime, greutate, grupa_sangvina
                )
                VALUES
                    (?, ?, ?, ?, '1990-01-01',
                     'MASCULIN', 175, 70.5, 'A (II) Rh+'),
                    (?, ?, ?, ?, '1990-01-01',
                     'MASCULIN', 170, 65, '0 (I) Rh+')
                """,
                pacientId, pacientUserId, medicId, CNP_INITIAL,
                altPacientId, altPacientUserId, altMedicId, CNP_ALT
        );
    }

    @Test
    void pacientulIsiVedeProfilulSiFormularulFaraScrieri()
            throws Exception {

        MockHttpSession sesiune = loginPacient();
        var initial = stareBaza();

        mvc.perform(get("/pacient/profil/{id}", pacientUserId)
                        .session(sesiune))
                .andExpect(status().isOk())
                .andExpect(view().name("pacient/profil"))
                .andExpect(model().attribute("mode", "view"))
                .andExpect(content().string(
                        containsString(EMAIL_PACIENT)
                ))
                .andExpect(model().attributeExists("whatsappLinkMedic"));

        mvc.perform(get(
                        "/pacient/profil/{id}/editare",
                        pacientUserId
                ).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(view().name("pacient/profil"))
                .andExpect(model().attribute("mode", "edit"))
                .andExpect(model().attributeExists("formular"))
                .andExpect(content().string(
                        containsString("value=\"" + CNP_INITIAL + "\"")
                ))
                .andExpect(content().string(
                        containsString("name=\"_csrf\"")
                ));

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void salvareaActualizeazaProfilulSiPastreazaCampurileProtejate()
            throws Exception {

        MockHttpSession sesiune = loginPacient();
        var initial = starePacient();

        var altiiInitial = stareBaza().stream()
                .filter(rand ->
                        !pacientUserId.equals(rand.get("user_id"))
                )
                .toList();

        mvc.perform(formular(sesiune, pacientUserId)
                        .param("rol", "ADMIN")
                        .param("parola", "Parola-care-nu-trebuie-salvata")
                        .param("userId", altPacientUserId.toString())
                        .param("pacientId", altPacientId.toString())
                        .param("medicId", altMedicId.toString())
                        .param("medic.userId", altMedicId.toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/pacient/profil/" + pacientUserId
                ))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Profilul medical a fost actualizat cu succes!"
                ));

        assertThat(starePacient())
                .containsEntry("pacient_id", pacientId)
                .containsEntry("medic_id", medicId)
                .containsEntry("nume", "NumeNou")
                .containsEntry("prenume", "PrenumeNou")
                .containsEntry("email", EMAIL_NOU)
                .containsEntry("cnp", CNP_NOU)
                .containsEntry("sex", "MASCULIN")
                .containsEntry("inaltime", 180)
                .containsEntry("greutate", 80.5)
                .containsEntry("grupa_sangvina", "B (III) Rh+")
                .containsEntry("rol", "PACIENT")
                .containsEntry("parola", initial.get("parola"));

        assertThat(stareBaza().stream()
                .filter(rand ->
                        !pacientUserId.equals(rand.get("user_id"))
                )
                .toList()).isEqualTo(altiiInitial);

        assertThat(sesiune.getAttribute("userNume"))
                .isEqualTo("NumeNou");
        assertThat(sesiune.getAttribute("userPrenume"))
                .isEqualTo("PrenumeNou");
        assertThat(sesiune.getAttribute("userEmail"))
                .isEqualTo(EMAIL_NOU);

        mvc.perform(get("/pacient/profil/{id}", pacientUserId)
                        .session(sesiune))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(EMAIL_NOU)));

        login(EMAIL_NOU, "PACIENT", pacientUserId);

        mvc.perform(post("/process-login")
                        .with(csrf())
                        .param("username", EMAIL_PACIENT)
                        .param("password", PAROLA))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=true"))
                .andExpect(unauthenticated());
    }

    @Test
    void formularulInvalidPastreazaValorileFaraScrieri()
            throws Exception {

        MockHttpSession sesiune = loginPacient();
        var initial = stareBaza();
        var sesiuneInitiala = stareSesiune(sesiune);

        mvc.perform(formular(sesiune, pacientUserId, Map.of(
                        "nume", " ",
                        "prenume", " ",
                        "email", "gresit",
                        "cnp", ""
                )).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("pacient/profil"))
                .andExpect(model().attributeHasFieldErrors(
                        "formular", "nume", "prenume", "email", "cnp"
                ))
                .andExpect(content().string(
                        containsString("value=\"gresit\"")
                ))
                .andExpect(content().string(
                        containsString("value=\"80.5\"")
                ));

        assertThat(stareBaza()).isEqualTo(initial);
        assertThat(stareSesiune(sesiune)).isEqualTo(sesiuneInitiala);
    }

    @ParameterizedTest
    @CsvSource({
            "cnp,1900101400013",
            "dataNasterii,2026-02-30",
            "sex,INVALID",
            "inaltime,abc",
            "inaltime,0",
            "greutate,NaN",
            "greutate,Infinity",
            "greutate,-1",
            "grupaSangvina,INVALID"
    })
    void valorileInvalideSuntRefuzateFaraScrieri(
            String camp,
            String valoare) throws Exception {

        MockHttpSession sesiune = loginPacient();
        var initial = stareBaza();
        var sesiuneInitiala = stareSesiune(sesiune);

        var rezultat = mvc.perform(formular(
                        sesiune,
                        pacientUserId,
                        Map.of(camp, valoare)
                ).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("pacient/profil"))
                .andExpect(model().attributeHasFieldErrors(
                        "formular", camp
                ))
                .andReturn();

        if (camp.equals("dataNasterii")) {
            assertThat(rezultat.getResponse().getContentAsString())
                    .contains("calendaristic");
        }

        assertThat(stareBaza()).isEqualTo(initial);
        assertThat(stareSesiune(sesiune)).isEqualTo(sesiuneInitiala);
    }

    @Test
    void dataNasteriiInViitorEsteRefuzata() throws Exception {
        MockHttpSession sesiune = loginPacient();
        var initial = stareBaza();

        String dataViitoare = LocalDate.now(
                ZoneId.of("Europe/Bucharest")
        ).plusYears(1).toString();

        mvc.perform(formular(sesiune, pacientUserId, Map.of(
                        "dataNasterii", dataViitoare
                )).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors(
                        "formular", "dataNasterii"
                ));

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void campurileOptionalePotFiGolinte() throws Exception {
        MockHttpSession sesiune = loginPacient();

        mvc.perform(formular(sesiune, pacientUserId, Map.of(
                        "dataNasterii", "",
                        "inaltime", "",
                        "greutate", "",
                        "grupaSangvina", ""
                )).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/pacient/profil/" + pacientUserId
                ));

        var stare = starePacient();

        assertThat(stare.get("data_nasterii")).isNull();
        assertThat(stare.get("inaltime")).isNull();
        assertThat(stare.get("greutate")).isNull();
        assertThat(stare.get("grupa_sangvina")).isNull();
        assertThat(stare.get("medic_id")).isEqualTo(medicId);
    }

    @Test
    void cnpInvalidNeschimbatEsteRefuzatLaSalvare()
            throws Exception {

        String cnpInvalid = "1234567890123";

        jdbc.update("""
                UPDATE public.pacienti
                SET cnp = ?
                WHERE id = ?
                """, cnpInvalid, pacientId);

        MockHttpSession sesiune = loginPacient();
        var initial = stareBaza();

        // Citirea datelor existente nu declanșează validare retroactivă.
        mvc.perform(get("/pacient/profil/{id}", pacientUserId)
                        .session(sesiune))
                .andExpect(status().isOk());

        mvc.perform(formular(sesiune, pacientUserId, Map.of(
                        "cnp", cnpInvalid
                )).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("pacient/profil"))
                .andExpect(model().attributeHasFieldErrors(
                        "formular", "cnp"
                ));

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "cnp"})
    void dateleAltuiPacientNuPotFiReutilizate(String camp)
            throws Exception {

        MockHttpSession sesiune = loginPacient();
        var initial = stareBaza();
        var sesiuneInitiala = stareSesiune(sesiune);

        String valoare = camp.equals("email")
                ? " ALT-PACIENT@EXAMPLE.TEST "
                : CNP_ALT;

        mvc.perform(formular(
                        sesiune,
                        pacientUserId,
                        Map.of(camp, valoare)
                ).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("pacient/profil"))
                .andExpect(model().attributeHasFieldErrors(
                        "formular", camp
                ));

        assertThat(stareBaza()).isEqualTo(initial);
        assertThat(stareSesiune(sesiune)).isEqualTo(sesiuneInitiala);
    }

    @ParameterizedTest
    @ValueSource(strings = {"email", "cnp"})
    void constrangerileDbProtejeazaDupaVerificareaPrealabila(
            String camp) throws Exception {

        MockHttpSession sesiune = loginPacient();
        var initial = stareBaza();
        var sesiuneInitiala = stareSesiune(sesiune);

        String valoare;

        if (camp.equals("email")) {
            valoare = EMAIL_ALT;

            doReturn(false).when(utilizatoriRepository)
                    .existsByEmailIgnoreCaseAndIdNot(
                            EMAIL_ALT,
                            pacientUserId
                    );
        } else {
            valoare = CNP_ALT;

            doReturn(false).when(pacientiRepository)
                    .existsByCnpAndIdNot(
                            CNP_ALT,
                            pacientId
                    );
        }

        // Simulăm verificarea prealabilă depășită.
        // Scrierea și constrângerile PostgreSQL sunt reale.
        mvc.perform(formular(
                        sesiune,
                        pacientUserId,
                        Map.of(camp, valoare)
                ).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("pacient/profil"))
                .andExpect(model().attributeHasFieldErrors(
                        "formular", camp
                ));

        if (camp.equals("email")) {
            verify(utilizatoriRepository)
                    .existsByEmailIgnoreCaseAndIdNot(
                            EMAIL_ALT,
                            pacientUserId
                    );
        } else {
            verify(pacientiRepository)
                    .existsByCnpAndIdNot(CNP_ALT, pacientId);
        }

        assertThat(stareBaza()).isEqualTo(initial);
        assertThat(stareSesiune(sesiune)).isEqualTo(sesiuneInitiala);
    }

    @Test
    void salvareaFaraCsrfEsteRefuzata() throws Exception {
        MockHttpSession sesiune = loginPacient();
        var initial = stareBaza();

        mvc.perform(formular(sesiune, pacientUserId))
                .andExpect(status().isForbidden());

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void pacientulNuPoateAccesaSauModificaAltProfil()
            throws Exception {

        MockHttpSession sesiune = loginPacient();
        var initial = stareBaza();

        verificaRefuzulOperatiilor(
                sesiune,
                altPacientUserId,
                403
        );

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "MEDIC"})
    void celelalteRoluriNuPotAccesaProfilulPacientului(String rol)
            throws Exception {

        UUID id = rol.equals("ADMIN") ? adminId : medicId;

        MockHttpSession sesiune = login(
                rol.toLowerCase(Locale.ROOT) + "@example.test",
                rol,
                id
        );

        var initial = stareBaza();

        verificaRefuzulOperatiilor(sesiune, pacientUserId, 403);

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void vizitatorulEsteTrimisLaAutentificare() throws Exception {
        var initial = stareBaza();

        mvc.perform(get("/pacient/profil/{id}", pacientUserId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));

        mvc.perform(post(
                        "/pacient/profil/{id}/salvare",
                        pacientUserId
                ).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void modificareaIduluiDinSesiuneNuSchimbaProprietarul()
            throws Exception {

        MockHttpSession sesiune = loginPacient();
        sesiune.setAttribute("userId", altPacientUserId.toString());

        var initial = stareBaza();

        verificaRefuzulOperatiilor(
                sesiune,
                altPacientUserId,
                403
        );

        mvc.perform(get(
                        "/pacient/profil/{id}/editare",
                        pacientUserId
                ).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("value=\"" + CNP_INITIAL + "\"")
                ));

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void rolulSchimbatInDbInvalideazaAccesulSesiuniiExistente()
            throws Exception {

        MockHttpSession sesiune = loginPacient();

        jdbc.update("""
                UPDATE public.utilizatori
                SET rol = 'MEDIC'
                WHERE id = ?
                """, pacientUserId);

        var initial = stareBaza();

        verificaRefuzulOperatiilor(sesiune, pacientUserId, 403);

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void profilulLipsaNuEsteCreatImplicit() throws Exception {
        MockHttpSession sesiune = loginPacient();

        jdbc.update(
                "DELETE FROM public.pacienti WHERE id = ?",
                pacientId
        );

        var initial = stareBaza();

        verificaRefuzulOperatiilor(sesiune, pacientUserId, 404);

        assertThat(stareBaza()).isEqualTo(initial);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM public.pacienti
                WHERE user_id = ?
                """, Long.class, pacientUserId)).isZero();
    }

    @Test
    void eroareaDupaScriereAnuleazaContulSiProfilul()
            throws Exception {

        MockHttpSession sesiune = loginPacient();
        var initial = stareBaza();
        var sesiuneInitiala = stareSesiune(sesiune);

        AtomicReference<Map<String, Object>> stareInTranzactie =
                new AtomicReference<>();

        doAnswer(invocare -> {
            entityManager.flush();

            // Confirmăm scrierile reale înaintea erorii simulate.
            stareInTranzactie.set(starePacient());

            throw new IllegalStateException(
                    "EROARE_SIMULATA_DUPA_FLUSH"
            );
        }).when(utilizatoriRepository).flush();

        Throwable eroareHttp = catchThrowable(() ->
                mvc.perform(formular(sesiune, pacientUserId)
                                .with(csrf()))
                        .andExpect(status().is5xxServerError())
        );

        assertThat(stareInTranzactie.get())
                .isNotNull()
                .containsEntry("nume", "NumeNou")
                .containsEntry("email", EMAIL_NOU)
                .containsEntry("cnp", CNP_NOU)
                .containsEntry("inaltime", 180)
                .containsEntry("greutate", 80.5)
                .containsEntry("medic_id", medicId);

        assertThat(stareBaza()).isEqualTo(initial);
        assertThat(stareSesiune(sesiune)).isEqualTo(sesiuneInitiala);

        if (eroareHttp != null) {
            assertThat(eroareHttp)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("EROARE_SIMULATA_DUPA_FLUSH");
        }
    }

    private void verificaRefuzulOperatiilor(
            MockHttpSession sesiune,
            UUID userId,
            int codHttp) throws Exception {

        for (String sufix : List.of("", "/editare")) {
            mvc.perform(get("/pacient/profil/" + userId + sufix)
                            .session(sesiune))
                    .andExpect(status().is(codHttp));
        }

        mvc.perform(formular(sesiune, userId).with(csrf()))
                .andExpect(status().is(codHttp));

        mvc.perform(formular(
                        sesiune,
                        userId,
                        Map.of("nume", " ")
                ).with(csrf()))
                .andExpect(status().is(codHttp));
    }

    private MockHttpServletRequestBuilder formular(
            MockHttpSession sesiune,
            UUID userId) {

        return formular(sesiune, userId, Map.of());
    }

    private MockHttpServletRequestBuilder formular(
            MockHttpSession sesiune,
            UUID userId,
            Map<String, String> modificari) {

        var parametri = new LinkedMultiValueMap<String, String>();

        parametri.set("nume", " NumeNou ");
        parametri.set("prenume", " PrenumeNou ");
        parametri.set("email", " NOU@EXAMPLE.TEST ");
        parametri.set("cnp", CNP_NOU);
        parametri.set("dataNasterii", "1990-01-01");
        parametri.set("sex", "MASCULIN");
        parametri.set("inaltime", "180");
        parametri.set("greutate", "80.5");
        parametri.set("grupaSangvina", "B (III) Rh+");

        modificari.forEach(parametri::set);

        return post("/pacient/profil/{id}/salvare", userId)
                .session(sesiune)
                .params(parametri);
    }

    private MockHttpSession loginPacient() throws Exception {
        return login(EMAIL_PACIENT, "PACIENT", pacientUserId);
    }

    private MockHttpSession login(
            String email,
            String rol,
            UUID userId) throws Exception {

        var rezultat = mvc.perform(post("/process-login")
                        .with(csrf())
                        .param("username", email)
                        .param("password", PAROLA))
                .andExpect(status().is3xxRedirection())
                .andExpect(authenticated()
                        .withUsername(email)
                        .withRoles(rol))
                .andExpect(redirectedUrl(
                        "/" + rol.toLowerCase(Locale.ROOT)
                                + "/dashboard/" + userId
                ))
                .andReturn();

        MockHttpSession sesiune = (MockHttpSession)
                rezultat.getRequest().getSession(false);

        assertThat(sesiune).isNotNull();

        return sesiune;
    }

    private void utilizator(
            UUID id,
            String email,
            String rol,
            String hash) {

        jdbc.update("""
                INSERT INTO public.utilizatori
                    (id, created_at, email, nume, prenume, rol, parola)
                VALUES (
                    ?, CURRENT_TIMESTAMP, ?,
                    'Initial', 'Cont', ?, ?
                )
                """, id, email, rol, hash);
    }

    private List<Map<String, Object>> stareBaza() {
        return jdbc.queryForList("""
                SELECT
                    u.id AS user_id,
                    u.nume, u.prenume, u.email, u.rol, u.parola,
                    p.id AS pacient_id,
                    p.medic_id, p.cnp, p.data_nasterii,
                    p.sex, p.inaltime, p.greutate, p.grupa_sangvina
                FROM public.utilizatori u
                LEFT JOIN public.pacienti p ON p.user_id = u.id
                ORDER BY u.id
                """);
    }

    private Map<String, Object> starePacient() {
        return stareBaza().stream()
                .filter(rand ->
                        pacientUserId.equals(rand.get("user_id"))
                )
                .findFirst()
                .orElseThrow();
    }

    private Map<String, Object> stareSesiune(MockHttpSession sesiune) {
        return Map.of(
                "userNume", sesiune.getAttribute("userNume"),
                "userPrenume", sesiune.getAttribute("userPrenume"),
                "userEmail", sesiune.getAttribute("userEmail")
        );
    }
}