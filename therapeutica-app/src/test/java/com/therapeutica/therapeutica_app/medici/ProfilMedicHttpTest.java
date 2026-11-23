package com.therapeutica.therapeutica_app.medici;

import com.therapeutica.therapeutica_app.utilizatori.UtilizatoriRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
class ProfilMedicHttpTest {

    private static final String PAROLA = "Parola-fictiva-Test-123!";
    private static final String EMAIL_MEDIC = "medic@example.test";
    private static final String EMAIL_ALT_MEDIC = "alt-medic@example.test";
    private static final String EMAIL_NOU = "nou@example.test";

    private static final Path DOCUMENTE = Path.of(
            System.getProperty("java.io.tmpdir"),
            "therapeutica-profil-medic-test-" + UUID.randomUUID()
    ).toAbsolutePath();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.1")
                    .withDatabaseName("therapeutica_profil_medic_test")
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

    private final UUID medicId = UUID.randomUUID();
    private final UUID altMedicId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID pacientUserId = UUID.randomUUID();

    @BeforeEach
    void pregatesteDatele() {
        // Curățăm exclusiv baza temporară a acestei clase.
        assertThat(jdbc.queryForObject(
                "SELECT current_database()", String.class
        )).isEqualTo(postgres.getDatabaseName());

        jdbc.execute("TRUNCATE TABLE public.utilizatori CASCADE");

        String hash = encoder.encode(PAROLA);

        utilizator(medicId, EMAIL_MEDIC, "MEDIC", hash);
        utilizator(altMedicId, EMAIL_ALT_MEDIC, "MEDIC", hash);
        utilizator(adminId, "admin@example.test", "ADMIN", hash);
        utilizator(pacientUserId, "pacient@example.test", "PACIENT", hash);

        jdbc.update("""
                INSERT INTO public.medici
                    (user_id, specializare, telefon)
                VALUES
                    (?, 'Medicina interna', '0700000000'),
                    (?, 'Neurologie', '0711111111')
                """, medicId, altMedicId);
    }

    @Test
    void mediculIsiVedeProfilulSiFormularulFaraScrieri()
            throws Exception {

        MockHttpSession sesiune = loginMedic();
        var initial = stareBaza();

        mvc.perform(get("/medic/profil/{id}", medicId)
                        .session(sesiune))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/profil"))
                .andExpect(model().attribute("mode", "view"))
                .andExpect(content().string(
                        containsString(EMAIL_MEDIC)
                ));

        mvc.perform(get("/medic/profil/{id}/editare", medicId)
                        .session(sesiune))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/profil"))
                .andExpect(model().attribute("mode", "edit"))
                .andExpect(model().attributeExists("formular"))
                .andExpect(content().string(
                        containsString("value=\"" + EMAIL_MEDIC + "\"")
                ))
                .andExpect(content().string(
                        containsString("name=\"_csrf\"")
                ));

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void salvareaActualizeazaProfilulSiPermiteLoginCuNoulEmail()
            throws Exception {

        MockHttpSession sesiune = loginMedic();
        var initial = stareMedic();

        mvc.perform(formular(sesiune, medicId)
                        .param("rol", "ADMIN")
                        .param("parola", "Parola-care-nu-trebuie-salvata")
                        .param("userId", altMedicId.toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/medic/profil/" + medicId
                ))
                .andExpect(flash().attribute(
                        "successMessage",
                        "Profilul a fost actualizat!"
                ));

        assertThat(stareMedic())
                .containsEntry("nume", "NumeNou")
                .containsEntry("prenume", "PrenumeNou")
                .containsEntry("email", EMAIL_NOU)
                .containsEntry("specializare", "Cardiologie")
                .containsEntry("telefon", "0722222222")
                .containsEntry("rol", "MEDIC")
                .containsEntry("parola", initial.get("parola"));

        assertThat(sesiune.getAttribute("userNume"))
                .isEqualTo("NumeNou");
        assertThat(sesiune.getAttribute("userPrenume"))
                .isEqualTo("PrenumeNou");
        assertThat(sesiune.getAttribute("userEmail"))
                .isEqualTo(EMAIL_NOU);

        // Identitatea existentă continuă să funcționeze prin UUID.
        mvc.perform(get("/medic/profil/{id}", medicId)
                        .session(sesiune))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString(EMAIL_NOU)
                ));

        login(EMAIL_NOU, "MEDIC", medicId);

        mvc.perform(post("/process-login")
                        .with(csrf())
                        .param("username", EMAIL_MEDIC)
                        .param("password", PAROLA))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?error=true"))
                .andExpect(unauthenticated());
    }

    @Test
    void formularulInvalidPastreazaValorileFaraScrieri()
            throws Exception {

        MockHttpSession sesiune = loginMedic();
        var initial = stareBaza();
        var sesiuneInitiala = stareSesiune(sesiune);

        mvc.perform(formular(sesiune, medicId, Map.of(
                        "nume", " ",
                        "prenume", " ",
                        "email", "gresit",
                        "telefon", "1".repeat(21)
                ))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/profil"))
                .andExpect(model().attribute("mode", "edit"))
                .andExpect(model().attributeHasFieldErrors(
                        "formular",
                        "nume",
                        "prenume",
                        "email",
                        "telefon"
                ))
                .andExpect(content().string(
                        containsString("value=\"gresit\"")
                ))
                .andExpect(content().string(
                        containsString("value=\"Cardiologie\"")
                ));

        assertThat(stareBaza()).isEqualTo(initial);
        assertThat(stareSesiune(sesiune)).isEqualTo(sesiuneInitiala);
    }

    @Test
    void emailulAltuiContEsteRefuzatIndiferentDeLitere()
            throws Exception {

        MockHttpSession sesiune = loginMedic();
        var initial = stareBaza();
        var sesiuneInitiala = stareSesiune(sesiune);

        mvc.perform(formular(sesiune, medicId, Map.of(
                        "email", " ALT-MEDIC@EXAMPLE.TEST "
                ))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/profil"))
                .andExpect(model().attributeHasFieldErrors(
                        "formular", "email"
                ));

        assertThat(stareBaza()).isEqualTo(initial);
        assertThat(stareSesiune(sesiune)).isEqualTo(sesiuneInitiala);
    }

    @Test
    void constrangereaDbProtejeazaSiDupaVerificareaPrealabila()
            throws Exception {

        MockHttpSession sesiune = loginMedic();
        var initial = stareBaza();
        var sesiuneInitiala = stareSesiune(sesiune);

        // Simulăm o verificare prealabilă devenită depășită.
        // Salvarea și constrângerea unică din PostgreSQL sunt reale.
        doReturn(false).when(utilizatoriRepository)
                .existsByEmailIgnoreCaseAndIdNot(
                        EMAIL_ALT_MEDIC,
                        medicId
                );

        mvc.perform(formular(sesiune, medicId, Map.of(
                        "email", EMAIL_ALT_MEDIC
                ))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(view().name("medic/profil"))
                .andExpect(model().attributeHasFieldErrors(
                        "formular", "email"
                ));

        assertThat(stareBaza()).isEqualTo(initial);
        assertThat(stareSesiune(sesiune)).isEqualTo(sesiuneInitiala);
    }

    @Test
    void salvareaFaraCsrfEsteRefuzata() throws Exception {
        MockHttpSession sesiune = loginMedic();
        var initial = stareBaza();

        mvc.perform(formular(sesiune, medicId))
                .andExpect(status().isForbidden());

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void mediculNuPoateAccesaSauModificaProfilulAltuiMedic()
            throws Exception {

        MockHttpSession sesiune = loginMedic();
        var initial = stareBaza();

        verificaRefuzulOperatiilor(sesiune, altMedicId, 403);

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "PACIENT"})
    void celelalteRoluriNuPotAccesaProfilulMedicului(String rol)
            throws Exception {

        UUID id = rol.equals("ADMIN") ? adminId : pacientUserId;

        MockHttpSession sesiune = login(
                rol.toLowerCase(Locale.ROOT) + "@example.test",
                rol,
                id
        );

        var initial = stareBaza();

        verificaRefuzulOperatiilor(sesiune, medicId, 403);

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void vizitatorulEsteTrimisLaAutentificare() throws Exception {
        var initial = stareBaza();

        mvc.perform(get("/medic/profil/{id}", medicId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));

        mvc.perform(post("/medic/profil/{id}/salvare", medicId)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void modificareaIduluiDinSesiuneNuSchimbaProprietarul()
            throws Exception {

        MockHttpSession sesiune = loginMedic();
        sesiune.setAttribute("userId", altMedicId.toString());

        var initial = stareBaza();

        verificaRefuzulOperatiilor(sesiune, altMedicId, 403);

        mvc.perform(get("/medic/profil/{id}/editare", medicId)
                        .session(sesiune))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        containsString("value=\"" + EMAIL_MEDIC + "\"")
                ));

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void rolulSchimbatInDbInvalideazaAccesulSesiuniiExistente()
            throws Exception {

        MockHttpSession sesiune = loginMedic();

        jdbc.update("""
                UPDATE public.utilizatori
                SET rol = 'PACIENT'
                WHERE id = ?
                """, medicId);

        var initial = stareBaza();

        verificaRefuzulOperatiilor(sesiune, medicId, 403);

        assertThat(stareBaza()).isEqualTo(initial);
    }

    @Test
    void profilulLipsaNuEsteCreatImplicit() throws Exception {
        MockHttpSession sesiune = loginMedic();

        jdbc.update(
                "DELETE FROM public.medici WHERE user_id = ?",
                medicId
        );

        var initial = stareBaza();

        verificaRefuzulOperatiilor(sesiune, medicId, 404);

        assertThat(stareBaza()).isEqualTo(initial);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM public.medici
                WHERE user_id = ?
                """, Long.class, medicId)).isZero();
    }

    @Test
    void eroareaDupaScriereAnuleazaContulSiProfilul()
            throws Exception {

        MockHttpSession sesiune = loginMedic();
        var initial = stareBaza();
        var sesiuneInitiala = stareSesiune(sesiune);

        AtomicReference<Map<String, Object>> stareInTranzactie =
                new AtomicReference<>();

        doAnswer(invocare -> {
            // Executăm scrierile reale în tranzacția serviciului.
            entityManager.flush();

            // Confirmăm datele scrise înainte de eroarea simulată.
            stareInTranzactie.set(stareMedic());

            throw new IllegalStateException(
                    "EROARE_SIMULATA_DUPA_FLUSH"
            );
        }).when(utilizatoriRepository).flush();

        Throwable eroareHttp = catchThrowable(() ->
                mvc.perform(formular(sesiune, medicId)
                                .with(csrf()))
                        .andExpect(status().is5xxServerError())
        );

        assertThat(stareInTranzactie.get())
                .isNotNull()
                .containsEntry("nume", "NumeNou")
                .containsEntry("email", EMAIL_NOU)
                .containsEntry("specializare", "Cardiologie")
                .containsEntry("telefon", "0722222222");

        // Citim din nou după încheierea cererii și a tranzacției.
        assertThat(stareBaza()).isEqualTo(initial);
        assertThat(stareSesiune(sesiune)).isEqualTo(sesiuneInitiala);

        // MockMvc poate propaga excepția sau poate primi răspunsul 500.
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
            mvc.perform(get("/medic/profil/" + userId + sufix)
                            .session(sesiune))
                    .andExpect(status().is(codHttp));
        }

        mvc.perform(formular(sesiune, userId).with(csrf()))
                .andExpect(status().is(codHttp));

        // Acoperim și ramura de formular invalid a controllerului.
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
        parametri.set("specializare", " Cardiologie ");
        parametri.set("telefon", " 0722222222 ");

        modificari.forEach(parametri::set);

        return post("/medic/profil/{id}/salvare", userId)
                .session(sesiune)
                .params(parametri);
    }

    private MockHttpSession loginMedic() throws Exception {
        return login(EMAIL_MEDIC, "MEDIC", medicId);
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
                    u.id, u.nume, u.prenume, u.email, u.rol, u.parola,
                    m.specializare, m.telefon
                FROM public.utilizatori u
                LEFT JOIN public.medici m ON m.user_id = u.id
                ORDER BY u.id
                """);
    }

    private Map<String, Object> stareMedic() {
        return stareBaza().stream()
                .filter(rand -> medicId.equals(rand.get("id")))
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