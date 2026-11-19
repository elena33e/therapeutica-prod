package com.therapeutica.therapeutica_app.analize_medicale;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.util.FileSystemUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.config.location=classpath:/application-http-test.yml"
})
@AutoConfigureMockMvc
@ActiveProfiles("http-test")
@Testcontainers
class DocumenteMedicaleHttpTest {

    private static final String PAROLA = "Parola-fictiva-Test-123!";

    private static final List<String> CONTURI = List.of(
            "admin", "medic-a", "medic-b", "pacient-a", "pacient-b"
    );

    private static final Path DOCUMENTE = Path.of(
            System.getProperty("java.io.tmpdir"),
            "therapeutica-documente-test-" + UUID.randomUUID()
    ).toAbsolutePath().normalize();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.1")
                    .withDatabaseName("therapeutica_documente_test")
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

    @MockitoSpyBean
    private DocumentMedicalRepository documentRepository;

    private final Map<String, UUID> utilizatori = new HashMap<>();

    private final UUID pacientA = UUID.randomUUID();
    private final UUID pacientB = UUID.randomUUID();
    private final UUID documentVechi = UUID.randomUUID();
    private final UUID documentImagini = UUID.randomUUID();

    private Path fisierVechi;
    private byte[] primaImagine;
    private byte[] aDouaImagine;

    @BeforeEach
    void pregatesteDatele() throws IOException {
        assertThat(jdbc.queryForObject(
                "SELECT current_database()", String.class
        )).isEqualTo(postgres.getDatabaseName());

        jdbc.execute("TRUNCATE TABLE public.utilizatori CASCADE");

        String hash = encoder.encode(PAROLA);

        for (String cont : CONTURI) {
            UUID userId = UUID.randomUUID();
            utilizatori.put(cont, userId);

            jdbc.update("""
                    INSERT INTO public.utilizatori
                        (id, created_at, email, nume, prenume, rol, parola)
                    VALUES (?, CURRENT_TIMESTAMP, ?, 'Test', 'Cont', ?, ?)
                    """, userId, email(cont), rol(cont), hash);
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

        primaImagine = imaginePng(0xFFFF0000);
        aDouaImagine = imaginePng(0xFF0000FF);

        Path directorTest = Files.createDirectories(
                DOCUMENTE.resolve(UUID.randomUUID().toString())
        );

        // Compatibilitate cu documentele vechi: cale directă la fișier.
        fisierVechi = directorTest.resolve("vechi.png");
        Files.write(fisierVechi, primaImagine);

        insereazaDocument(
                documentVechi,
                fisierVechi,
                "Document vechi"
        );

        // Compatibilitate cu documentele formate din mai multe imagini.
        Path folderImagini = Files.createDirectory(
                directorTest.resolve("imagini")
        );

        // Creăm intenționat fișierele în ordine inversă.
        Files.write(folderImagini.resolve("0002_a_doua.png"), aDouaImagine);
        Files.write(folderImagini.resolve("0001_prima.png"), primaImagine);

        insereazaDocument(
                documentImagini,
                folderImagini,
                "Document cu imagini"
        );
    }

    @AfterAll
    static void curataFisiereleTemporare() throws IOException {
        // Director unic, creat exclusiv pentru această clasă de teste.
        FileSystemUtils.deleteRecursively(DOCUMENTE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pacient-a", "medic-a"})
    void conturileAutorizateVadDosarulSiDocumentulVechi(String cont)
            throws Exception {

        MockHttpSession sesiune = login(cont);

        mvc.perform(get(dosar(cont)).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(model().attribute("documente", hasSize(2)));

        mvc.perform(get(
                        "/documente/{id}/sursa", documentVechi
                ).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(view().name("documente/sursa"))
                .andExpect(model().attribute(
                        "numeFisiere", List.of("vechi.png")
                ));

        mvc.perform(get(
                        "/documente/{id}/fisier", documentVechi
                ).session(sesiune))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(content().bytes(primaImagine))
                .andExpect(header().string(
                        HttpHeaders.CACHE_CONTROL,
                        containsString("no-store")
                ))
                .andExpect(header().string(
                        HttpHeaders.CONTENT_DISPOSITION,
                        startsWith("inline")
                ));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM public.audit_log
                WHERE utilizator_id = ?
                  AND resursa_id = ?
                  AND tip_resursa = 'DOCUMENT_MEDICAL'
                  AND actiune = 'VIZUALIZARE'
                """, Long.class, id(cont), documentVechi))
                .isGreaterThanOrEqualTo(1L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pacient-a", "medic-a"})
    void imaginileSuntAfisateInOrdineaStabila(String cont)
            throws Exception {

        MockHttpSession sesiune = login(cont);

        mvc.perform(get(
                        "/documente/{id}/sursa", documentImagini
                )
                        .param("index", "1")
                        .session(sesiune))
                .andExpect(status().isOk())
                .andExpect(model().attribute(
                        "numeFisiere",
                        List.of("0001_prima.png", "0002_a_doua.png")
                ))
                .andExpect(model().attribute("indexSelectat", 1));

        mvc.perform(get(
                        "/documente/{id}/fisier", documentImagini
                )
                        .param("index", "0")
                        .session(sesiune))
                .andExpect(status().isOk())
                .andExpect(content().bytes(primaImagine));

        mvc.perform(get(
                        "/documente/{id}/fisier", documentImagini
                )
                        .param("index", "1")
                        .session(sesiune))
                .andExpect(status().isOk())
                .andExpect(content().bytes(aDouaImagine));
    }

    @Test
    void pacientulIncarcaPdfIarConturileAutorizatePrimescOriginalul()
            throws Exception {

        byte[] original = pdfDeTest();

        mvc.perform(multipart("/analize/incarca")
                        .file(new MockMultipartFile(
                                "files",
                                "analiza.pdf",
                                "application/pdf",
                                original
                        ))
                        .param("pacientId", id("pacient-a").toString())
                        .session(login("pacient-a"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(dosar("pacient-a")))
                .andExpect(flash().attributeExists("success"));

        UUID documentNou = idDocumentNou();
        Path folder = caleDocument(documentNou);

        assertThat(folder).startsWith(DOCUMENTE);

        try (var continut = Files.list(folder)) {
            assertThat(continut.map(p -> p.getFileName().toString()).toList())
                    .containsExactly("0001_analiza.pdf");
        }

        assertThat(Files.readAllBytes(
                folder.resolve("0001_analiza.pdf")
        )).isEqualTo(original);

        for (String cont : List.of("pacient-a", "medic-a")) {
            mvc.perform(get(
                            "/documente/{id}/fisier", documentNou
                    )
                            .param("descarcare", "true")
                            .session(login(cont)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                    .andExpect(content().bytes(original))
                    .andExpect(header().string(
                            HttpHeaders.CONTENT_DISPOSITION,
                            startsWith("attachment")
                    ));
        }

        assertThat(numarDocumente()).isEqualTo(3L);
    }

    @Test
    void incarcareaMaiMultorImaginiPastreazaOrdineaSiNumeleSigure()
            throws Exception {

        mvc.perform(multipart("/analize/incarca")
                        .file(new MockMultipartFile(
                                "files", "../../z.png",
                                "image/png", primaImagine
                        ))
                        .file(new MockMultipartFile(
                                "files", "a.png",
                                "image/png", aDouaImagine
                        ))
                        .param("pacientId", id("pacient-a").toString())
                        .session(login("pacient-a"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("success"));

        UUID documentNou = idDocumentNou();
        Path folder = caleDocument(documentNou);

        assertThat(folder).startsWith(DOCUMENTE);

        try (var continut = Files.list(folder)) {
            assertThat(continut
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList())
                    .containsExactly("0001_z.png", "0002_a.png");
        }

        assertThat(Files.readAllBytes(folder.resolve("0001_z.png")))
                .isEqualTo(primaImagine);

        assertThat(Files.readAllBytes(folder.resolve("0002_a.png")))
                .isEqualTo(aDouaImagine);

        assertThat(numarDocumente()).isEqualTo(3L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pacient-b", "medic-b", "admin"})
    void celelalteConturiNuPotCitiIncarcaSauRetrageDocumentele(
            String cont) throws Exception {

        MockHttpSession sesiune = login(cont);
        Set<Path> fisiereInitiale = inventarStocare();

        mvc.perform(get(
                        "/documente/{id}/sursa", documentVechi
                ).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(get(
                        "/documente/{id}/fisier", documentVechi
                ).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(get(
                        "/analize/pacient/documente/{id}",
                        id("pacient-a")
                ).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(get(
                        "/medic/analize/dosar/{id}", pacientA
                ).session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(multipart("/analize/incarca")
                        .file(fisierImagine())
                        .param("pacientId", id("pacient-a").toString())
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mvc.perform(post(
                        "/analize/sterge/{id}", documentVechi
                )
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verificaDocumenteNemodificate(fisiereInitiale);
    }

    @Test
    void mediculAlocatPoateCitiDarNuPoateIncarcaSauRetrageInLoculPacientului()
            throws Exception {

        MockHttpSession sesiune = login("medic-a");
        Set<Path> fisiereInitiale = inventarStocare();

        mvc.perform(multipart("/analize/incarca")
                        .file(fisierImagine())
                        .param("pacientId", id("pacient-a").toString())
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mvc.perform(post(
                        "/analize/sterge/{id}", documentVechi
                )
                        .session(sesiune)
                        .with(csrf()))
                .andExpect(status().isForbidden());

        verificaDocumenteNemodificate(fisiereInitiale);
    }

    @Test
    void vizitatorulEsteTrimisLaLoginFaraModificari()
            throws Exception {

        Set<Path> fisiereInitiale = inventarStocare();

        for (String cale : List.of(
                "/documente/" + documentVechi + "/sursa",
                "/documente/" + documentVechi + "/fisier",
                dosar("pacient-a"),
                dosar("medic-a")
        )) {
            mvc.perform(get(cale))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost/login"));
        }

        mvc.perform(multipart("/analize/incarca")
                        .file(fisierImagine())
                        .param("pacientId", id("pacient-a").toString())
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));

        mvc.perform(post("/analize/sterge/{id}", documentVechi)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost/login"));

        verificaDocumenteNemodificate(fisiereInitiale);
    }

    @Test
    void incarcareaSiRetragereaFaraCsrfSuntRefuzate()
            throws Exception {

        MockHttpSession sesiune = login("pacient-a");
        Set<Path> fisiereInitiale = inventarStocare();

        mvc.perform(multipart("/analize/incarca")
                        .file(fisierImagine())
                        .param("pacientId", id("pacient-a").toString())
                        .session(sesiune))
                .andExpect(status().isForbidden());

        mvc.perform(post(
                        "/analize/sterge/{id}", documentVechi
                ).session(sesiune))
                .andExpect(status().isForbidden());

        verificaDocumenteNemodificate(fisiereInitiale);
    }

    @Test
    void retragereaAscundeDocumentulDarPastreazaFisierul()
            throws Exception {

        MockHttpSession pacient = login("pacient-a");
        MockHttpSession medic = login("medic-a");

        mvc.perform(post(
                        "/analize/sterge/{id}", documentVechi
                )
                        .session(pacient)
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(dosar("pacient-a")))
                .andExpect(flash().attributeExists("success"));

        assertThat(statusDocument(documentVechi))
                .isEqualTo("STERS_DE_PACIENT");

        assertThat(Files.readAllBytes(fisierVechi))
                .isEqualTo(primaImagine);

        assertThat(numarDocumente()).isEqualTo(2L);

        mvc.perform(get(dosar("pacient-a")).session(pacient))
                .andExpect(status().isOk())
                .andExpect(model().attribute("documente", hasSize(1)));

        mvc.perform(get(dosar("medic-a")).session(medic))
                .andExpect(status().isOk())
                .andExpect(model().attribute("documente", hasSize(1)));

        for (MockHttpSession sesiune : List.of(pacient, medic)) {
            mvc.perform(get(
                            "/documente/{id}/sursa", documentVechi
                    ).session(sesiune))
                    .andExpect(status().isNotFound());

            mvc.perform(get(
                            "/documente/{id}/fisier", documentVechi
                    ).session(sesiune))
                    .andExpect(status().isNotFound());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "pacient-a,-1",
            "pacient-a,2",
            "medic-a,-1",
            "medic-a,2"
    })
    void unIndexInexistentReturneaza404(String cont, int index)
            throws Exception {

        MockHttpSession sesiune = login(cont);

        for (String operatie : List.of("sursa", "fisier")) {
            mvc.perform(get(
                            "/documente/{id}/" + operatie,
                            documentImagini
                    )
                            .param("index", Integer.toString(index))
                            .session(sesiune))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    void fisierulLipsaReturneaza404FaraStergereaInregistrarii()
            throws Exception {

        Files.delete(fisierVechi);

        for (String cont : List.of("pacient-a", "medic-a")) {
            MockHttpSession sesiune = login(cont);

            for (String operatie : List.of("sursa", "fisier")) {
                mvc.perform(get(
                                "/documente/{id}/" + operatie,
                                documentVechi
                        ).session(sesiune))
                        .andExpect(status().isNotFound());
            }
        }

        assertThat(statusDocument(documentVechi)).isEqualTo("INCARCAT");
        assertThat(numarDocumente()).isEqualTo(2L);
    }

    @Test
    void eroareaLaSalvareAnuleazaInregistrareaSiCurataFisierele()
            throws Exception {

        Set<Path> fisiereInitiale = inventarStocare();

        doThrow(new DataIntegrityViolationException(
                "Eroare simulata exclusiv in test"
        )).when(documentRepository).saveAndFlush(any(DocumentMedical.class));

        mvc.perform(multipart("/analize/incarca")
                        .file(fisierImagine())
                        .param("pacientId", id("pacient-a").toString())
                        .session(login("pacient-a"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(dosar("pacient-a")))
                .andExpect(flash().attributeExists("error"))
                .andExpect(flash().attributeCount(1));

        verificaDocumenteNemodificate(fisiereInitiale);
    }

    @Test
    void oExtensieNepermisaEsteRefuzataInainteDeStocare()
            throws Exception {

        Set<Path> fisiereInitiale = inventarStocare();

        mvc.perform(multipart("/analize/incarca")
                        .file(new MockMultipartFile(
                                "files",
                                "program.exe",
                                "application/octet-stream",
                                new byte[]{1, 2, 3}
                        ))
                        .param("pacientId", id("pacient-a").toString())
                        .session(login("pacient-a"))
                        .with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(flash().attributeExists("error"));

        verificaDocumenteNemodificate(fisiereInitiale);
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

    private void insereazaDocument(
            UUID documentId,
            Path cale,
            String nume) {

        jdbc.update("""
                INSERT INTO public.documente_medicale
                    (id, pacient_id, nume_fisier, cale_fisier_stocare,
                     data_incarcare, status, version)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP, 'INCARCAT', 0)
                """,
                documentId, pacientA, nume, cale.toString());
    }

    private void verificaDocumenteNemodificate(Set<Path> inventarInitial)
            throws IOException {

        assertThat(numarDocumente()).isEqualTo(2L);
        assertThat(statusDocument(documentVechi)).isEqualTo("INCARCAT");
        assertThat(statusDocument(documentImagini)).isEqualTo("INCARCAT");
        assertThat(inventarStocare()).isEqualTo(inventarInitial);
        assertThat(Files.readAllBytes(fisierVechi)).isEqualTo(primaImagine);
    }

    private Set<Path> inventarStocare() throws IOException {
        try (var continut = Files.walk(DOCUMENTE)) {
            return continut
                    .filter(path -> !path.equals(DOCUMENTE))
                    .map(DOCUMENTE::relativize)
                    .collect(Collectors.toSet());
        }
    }

    private UUID idDocumentNou() {
        return jdbc.queryForObject("""
                SELECT id FROM public.documente_medicale
                WHERE id NOT IN (?, ?)
                """, UUID.class, documentVechi, documentImagini);
    }

    private Path caleDocument(UUID documentId) {
        String cale = jdbc.queryForObject("""
                SELECT cale_fisier_stocare
                FROM public.documente_medicale WHERE id = ?
                """, String.class, documentId);

        return Path.of(cale).toAbsolutePath().normalize();
    }

    private String statusDocument(UUID documentId) {
        return jdbc.queryForObject("""
                SELECT status FROM public.documente_medicale WHERE id = ?
                """, String.class, documentId);
    }

    private long numarDocumente() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM public.documente_medicale",
                Long.class
        );
    }

    private MockMultipartFile fisierImagine() {
        return new MockMultipartFile(
                "files", "analiza.png", "image/png", primaImagine
        );
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

    private String dosar(String cont) {
        if (cont.startsWith("medic")) {
            return "/medic/analize/dosar/" + pacientA;
        }

        return "/analize/pacient/documente/" + id("pacient-a");
    }

    private static byte[] imaginePng(int culoare) throws IOException {
        BufferedImage imagine = new BufferedImage(
                1, 1, BufferedImage.TYPE_INT_ARGB
        );
        imagine.setRGB(0, 0, culoare);

        try (ByteArrayOutputStream iesire = new ByteArrayOutputStream()) {
            if (!ImageIO.write(imagine, "png", iesire)) {
                throw new IOException("Nu se poate genera imaginea de test.");
            }
            return iesire.toByteArray();
        }
    }

    private static byte[] pdfDeTest() {
        String continut = "BT /F1 12 Tf 20 100 Td (Test) Tj ET\n";

        List<String> obiecte = List.of(
                "<< /Type /Catalog /Pages 2 0 R >>",
                "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                """
                << /Type /Page /Parent 2 0 R
                   /MediaBox [0 0 200 200]
                   /Resources << /Font << /F1 5 0 R >> >>
                   /Contents 4 0 R >>
                """,
                "<< /Length " + continut.length()
                        + " >>\nstream\n" + continut + "endstream",
                "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        );

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        List<Integer> pozitii = new ArrayList<>();

        for (int i = 0; i < obiecte.size(); i++) {
            pozitii.add(pdf.length());

            pdf.append(i + 1).append(" 0 obj\n")
                    .append(obiecte.get(i))
                    .append("\nendobj\n");
        }

        int pozitieXref = pdf.length();

        pdf.append("xref\n0 6\n")
                .append("0000000000 65535 f \n");

        for (int pozitie : pozitii) {
            pdf.append(String.format(
                    Locale.ROOT, "%010d 00000 n \n", pozitie
            ));
        }

        pdf.append("trailer\n<< /Size 6 /Root 1 0 R >>\n")
                .append("startxref\n")
                .append(pozitieXref)
                .append("\n%%EOF\n");

        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }
}