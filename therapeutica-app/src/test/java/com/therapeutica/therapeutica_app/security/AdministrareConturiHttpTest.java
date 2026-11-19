package com.therapeutica.therapeutica_app.security;

import com.therapeutica.therapeutica_app.admin.AdministratorInitialService;
import com.therapeutica.therapeutica_app.medici.Medici;
import com.therapeutica.therapeutica_app.medici.MediciRepository;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-http-test.yml"
})
@AutoConfigureMockMvc
@ActiveProfiles("http-test")
@Testcontainers
class AdministrareConturiHttpTest {

    private static final String PAROLA = "Parola-fictiva-Test-123!";
    private static final String EMAIL_NOU = "invitat@example.test";
    private static final String CNP_NOU = "1234567890123";

    private static final Path DOCUMENTE = Path.of(
            System.getProperty("java.io.tmpdir"),
            "therapeutica-admin-test-" + UUID.randomUUID()
    ).toAbsolutePath();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.1")
                    .withDatabaseName("therapeutica_admin_test")
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
    private AdministratorInitialService administratorInitialService;

    @MockitoBean
    private JavaMailSender mailSender;

    @MockitoSpyBean
    private MediciRepository mediciRepository;

    private final UUID adminId = UUID.randomUUID();
    private final UUID medicId = UUID.randomUUID();
    private final UUID altMedicId = UUID.randomUUID();
    private final UUID pacientUserId = UUID.randomUUID();
    private final UUID pacientId = UUID.randomUUID();

    @BeforeEach
    void pregatesteDatele() {
        // Curățarea este permisă numai în containerul acestui test.
        assertThat(jdbc.queryForObject(
                "SELECT current_database()", String.class
        )).isEqualTo(postgres.getDatabaseName());

        jdbc.execute("TRUNCATE TABLE public.utilizatori CASCADE");

        String hash = encoder.encode(PAROLA);

        utilizator(adminId, "admin@example.test", "ADMIN", hash);
        utilizator(medicId, "medic@example.test", "MEDIC", hash);
        utilizator(altMedicId, "alt-medic@example.test", "MEDIC", hash);
        utilizator(pacientUserId, "pacient@example.test", "PACIENT", hash);

        jdbc.update("""
                INSERT INTO public.medici (user_id, specializare)
                VALUES (?, 'Test'), (?, 'Test')
                """, medicId, altMedicId);

        jdbc.update("""
                INSERT INTO public.pacienti (id, user_id, medic_id, sex)
                VALUES (?, ?, ?, 'NEPRECIZAT')
                """, pacientId, pacientUserId, medicId);
    }

    @Test
    void administratorulSeAutentificaSiVedeEvidentaMedicilor()
            throws Exception {

        MockHttpSession sesiune = login("ADMIN");

        mvc.perform(get("/admin/dashboard/{id}", adminId)
                        .session(sesiune))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/dashboard"))
                .andExpect(model().attribute("medici", hasSize(2)));

        mvc.perform(get("/admin/dashboard/{id}", medicId)
                        .session(sesiune))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"MEDIC", "PACIENT"})
    void celelalteRoluriNuPotAccesaAdministrarea(String rol)
            throws Exception {

        mvc.perform(get("/admin/dashboard/{id}", adminId)
                        .session(login(rol)))
                .andExpect(status().isForbidden());
    }

    @Test
    void vizitatorulEsteTrimisLaAutentificare() throws Exception {
        mvc.perform(get("/admin/dashboard/{id}", adminId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));
    }

    @Test
    void administratorulNuPrimesteAccesLaProfilurileMedicale()
            throws Exception {

        MockHttpSession sesiune = login("ADMIN");

        for (String ruta : new String[]{
                "/medic/profil/" + medicId,
                "/pacient/profil/" + pacientUserId,
                "/documente/test",
                "/analize/test"
        }) {
            mvc.perform(get(ruta).session(sesiune))
                    .andExpect(status().isForbidden());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "ADMIN,PACIENT",
            "ADMIN,ADMIN",
            "MEDIC,MEDIC",
            "MEDIC,ADMIN"
    })
    void rolulInvitatieiNuPoateFiModificatDinFormular(
            String autor,
            String destinatar) throws Exception {

        mvc.perform(formularInvitatie(
                        login(autor), destinatar, CNP_NOU
                ).with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(numarCoduri()).isZero();
    }

    @Test
    void administratorulInvitaMedicSiActivareaCreeazaProfilul()
            throws Exception {

        Map<String, Object> cod = genereaza(login("ADMIN"), "MEDIC");

        assertThat(cod.get("generat_de")).isEqualTo(adminId);
        assertThat(cod.get("rol_destinatar")).isEqualTo("MEDIC");

        mvc.perform(formularActivare(cod, CNP_NOU).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?inregistrare=succes"));

        UUID noulId = idUtilizatorNou();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.medici WHERE user_id = ?
                """, Long.class, noulId)).isEqualTo(1L);

        assertThat(jdbc.queryForObject("""
                SELECT rol FROM public.utilizatori WHERE id = ?
                """, String.class, noulId)).isEqualTo("MEDIC");

        String hash = jdbc.queryForObject("""
                SELECT parola FROM public.utilizatori WHERE id = ?
                """, String.class, noulId);

        assertThat(hash).isNotEqualTo(PAROLA);
        assertThat(encoder.matches(PAROLA, hash)).isTrue();

        login(EMAIL_NOU, "MEDIC", noulId);
        verificaCodUtilizat(cod, noulId);
    }

    @Test
    void mediculInvitaPacientAsociatLuiIgnorandAutorulDinFormular()
            throws Exception {

        Map<String, Object> cod = genereaza(login("MEDIC"), "PACIENT");

        // Formularul trimite intenționat ID-ul altui medic.
        assertThat(cod.get("generat_de")).isEqualTo(medicId);

        mvc.perform(formularActivare(cod, CNP_NOU).with(csrf()))
                .andExpect(status().is3xxRedirection());

        UUID noulId = idUtilizatorNou();

        assertThat(jdbc.queryForObject("""
                SELECT medic_id
                FROM public.pacienti
                WHERE user_id = ?
                """, UUID.class, noulId)).isEqualTo(medicId);

        assertThat(jdbc.queryForObject("""
                SELECT rol FROM public.utilizatori WHERE id = ?
                """, String.class, noulId)).isEqualTo("PACIENT");

        verificaCodUtilizat(cod, noulId);
    }

    @Test
    void generareaFaraCsrfEsteRefuzata() throws Exception {
        mvc.perform(formularInvitatie(
                        login("ADMIN"), "MEDIC", CNP_NOU
                ))
                .andExpect(status().isForbidden());

        assertThat(numarCoduri()).isZero();
    }

    @Test
    void cnpInvalidNuProduceInvitatie() throws Exception {
        mvc.perform(formularInvitatie(
                        login("ADMIN"), "MEDIC", "123"
                ).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("rezultat"));

        assertThat(numarCoduri()).isZero();
    }

    @Test
    void cnpGresitLaActivareNuCreeazaCont() throws Exception {
        var cod = genereaza(login("ADMIN"), "MEDIC");

        mvc.perform(formularActivare(cod, "9999999999999").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("eroare"));

        verificaLipsaContuluiNou();
        verificaCodNeutilizat(cod);
    }

    @Test
    void activareaFaraCsrfEsteRefuzata() throws Exception {
        var cod = genereaza(login("ADMIN"), "MEDIC");

        mvc.perform(formularActivare(cod, CNP_NOU))
                .andExpect(status().isForbidden());

        verificaLipsaContuluiNou();
        verificaCodNeutilizat(cod);
    }

    @Test
    void invitatiaUtilizataNuPoateFiActivataDinNouSauAnulata()
            throws Exception {

        MockHttpSession sesiune = login("ADMIN");
        var cod = genereaza(sesiune, "MEDIC");

        mvc.perform(formularActivare(cod, CNP_NOU).with(csrf()))
                .andExpect(status().is3xxRedirection());

        UUID noulId = idUtilizatorNou();

        mvc.perform(formularActivare(cod, CNP_NOU).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("eroare"));

        mvc.perform(post("/generare-cod/{id}/anuleaza", cod.get("id"))
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isConflict());

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.utilizatori WHERE email = ?
                """, Long.class, EMAIL_NOU)).isEqualTo(1L);

        verificaCodUtilizat(cod, noulId);
    }

    @Test
    void eroareaProfiluluiAnuleazaSiUtilizatorulScrisInBaza()
            throws Exception {

        var cod = genereaza(login("ADMIN"), "MEDIC");

        doThrow(new IllegalStateException("Eroare simulată"))
                .when(mediciRepository)
                .saveAndFlush(any(Medici.class));

        mvc.perform(formularActivare(cod, CNP_NOU).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(model().attributeExists("eroare"));

        verificaLipsaContuluiNou();
        verificaCodNeutilizat(cod);
    }

    @Test
    void invitatiaNeutilizataPoateFiAnulataDeAdministrator()
            throws Exception {

        MockHttpSession sesiune = login("ADMIN");
        var cod = genereaza(sesiune, "MEDIC");

        mvc.perform(post("/generare-cod/{id}/anuleaza", cod.get("id"))
                        .session(login("MEDIC"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        assertThat(numarCoduri()).isEqualTo(1L);

        mvc.perform(post("/generare-cod/{id}/anuleaza", cod.get("id"))
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/generare-cod"));

        assertThat(numarCoduri()).isZero();
    }

    @Test
    void primulAdministratorEsteCreatODataFaraResetareaParolei()
            throws Exception {

        jdbc.update(
                "DELETE FROM public.utilizatori WHERE id = ?",
                adminId
        );

        assertThat(administratorInitialService.creeaza(
                "initial@example.test", "Admin", "Initial", PAROLA
        )).isTrue();

        assertThat(administratorInitialService.creeaza(
                "alt-admin@example.test", "Alt", "Admin", "Alta-parola-123!"
        )).isFalse();

        UUID initialId = jdbc.queryForObject("""
                SELECT id FROM public.utilizatori WHERE rol = 'ADMIN'
                """, UUID.class);

        login("initial@example.test", "ADMIN", initialId);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.utilizatori WHERE rol = 'ADMIN'
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void initializareaNuTransformaUnMedicInAdministrator() {
        jdbc.update(
                "DELETE FROM public.utilizatori WHERE id = ?",
                adminId
        );

        assertThatThrownBy(() ->
                administratorInitialService.creeaza(
                        "medic@example.test", "Admin", "Initial", PAROLA
                )
        ).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.queryForObject("""
                SELECT rol FROM public.utilizatori WHERE id = ?
                """, String.class, medicId)).isEqualTo("MEDIC");

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.utilizatori WHERE rol = 'ADMIN'
                """, Long.class)).isZero();
    }

    @Test
    void apiurileVechiDeConturiSuntRefuzate() throws Exception {
        for (String rol : new String[]{"ADMIN", "MEDIC", "PACIENT"}) {
            MockHttpSession sesiune = login(rol);

            for (String ruta : new String[]{
                    "/api/utilizatoris",
                    "/api/medicis",
                    "/api/coduri-inregistrare"
            }) {
                mvc.perform(get(ruta).session(sesiune))
                        .andExpect(status().isForbidden());
            }
        }
    }

    private Map<String, Object> genereaza(
            MockHttpSession sesiune,
            String rol) throws Exception {

        mvc.perform(formularInvitatie(sesiune, rol, CNP_NOU).with(csrf()))
                .andExpect(status().isOk());

        return jdbc.queryForMap("""
                SELECT * FROM public.cod_inregistrare
                WHERE email_destinatar = ?
                """, EMAIL_NOU);
    }

    private MockHttpServletRequestBuilder formularInvitatie(
            MockHttpSession sesiune,
            String rol,
            String cnp) {

        return post("/generare-cod")
                .session(sesiune)
                .param("medicId", altMedicId.toString())
                .param("rolDestinatar", rol)
                .param("emailDestinatar", EMAIL_NOU)
                .param("numeDestinatar", "Invitat")
                .param("prenumeDestinatar", "Test")
                .param("cnpDestinatar", cnp);
    }

    private MockHttpServletRequestBuilder formularActivare(
            Map<String, Object> cod,
            String cnp) {

        return post("/inregistrare")
                .param("codUnic", (String) cod.get("cod_unic"))
                .param("cnp", cnp)
                .param("parola", PAROLA)
                .param("confirmaParola", PAROLA)
                .param("rol", "ADMIN");
    }

    private MockHttpSession login(String rol) throws Exception {
        return switch (rol) {
            case "ADMIN" ->
                    login("admin@example.test", rol, adminId);
            case "MEDIC" ->
                    login("medic@example.test", rol, medicId);
            case "PACIENT" ->
                    login("pacient@example.test", rol, pacientUserId);
            default -> throw new IllegalArgumentException("Rol de test invalid");
        };
    }

    private MockHttpSession login(
            String email,
            String rol,
            UUID id) throws Exception {

        var rezultat = mvc.perform(post("/process-login")
                        .with(csrf())
                        .param("username", email)
                        .param("password", PAROLA))
                .andExpect(status().is3xxRedirection())
                .andExpect(authenticated()
                        .withUsername(email)
                        .withRoles(rol))
                .andExpect(redirectedUrl(
                        "/" + rol.toLowerCase(java.util.Locale.ROOT)
                                + "/dashboard/" + id
                ))
                .andReturn();

        MockHttpSession sesiune = (MockHttpSession)
                rezultat.getRequest().getSession(false);

        assertThat(sesiune).isNotNull();
        assertThat(sesiune.getAttribute("userId"))
                .isEqualTo(id.toString());
        assertThat(sesiune.getAttribute("userRole")).isEqualTo(rol);

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
                VALUES (?, CURRENT_TIMESTAMP, ?, 'Test', 'Cont', ?, ?)
                """, id, email, rol, hash);
    }

    private UUID idUtilizatorNou() {
        return jdbc.queryForObject("""
                SELECT id FROM public.utilizatori WHERE email = ?
                """, UUID.class, EMAIL_NOU);
    }

    private long numarCoduri() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.cod_inregistrare",
                Long.class
        );
    }

    private void verificaLipsaContuluiNou() {
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.utilizatori WHERE email = ?
                """, Long.class, EMAIL_NOU)).isZero();
    }

    private void verificaCodNeutilizat(Map<String, Object> cod) {
        var stare = jdbc.queryForMap("""
                SELECT status, atribuit FROM public.cod_inregistrare
                WHERE id = ?
                """, cod.get("id"));

        assertThat(stare.get("status")).isEqualTo("NEUTILIZAT");
        assertThat(stare.get("atribuit")).isNull();
    }

    private void verificaCodUtilizat(
            Map<String, Object> cod,
            UUID utilizatorId) {

        var stare = jdbc.queryForMap("""
                SELECT status, atribuit FROM public.cod_inregistrare
                WHERE id = ?
                """, cod.get("id"));

        assertThat(stare.get("status")).isEqualTo("UTILIZAT");
        assertThat(stare.get("atribuit")).isEqualTo(utilizatorId);
    }
}