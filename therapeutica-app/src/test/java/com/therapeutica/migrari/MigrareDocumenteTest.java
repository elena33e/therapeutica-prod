package com.therapeutica.migrari;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.FileSystemUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        classes = ComandaMigrareDocumente.Configuratie.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.config.location=classpath:/application-http-test.yml"
        }
)
@ActiveProfiles("http-test")
@Testcontainers
class MigrareDocumenteTest {

    private static final UUID DOCUMENT_ACTIV =
            UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final UUID DOCUMENT_RETRAS =
            UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static final Path RADACINA_TEST = Path.of(
            System.getProperty("java.io.tmpdir"),
            "therapeutica-migrare-integrare-" + UUID.randomUUID()
    ).toAbsolutePath().normalize();

    private static final Path SURSA = RADACINA_TEST.resolve("sursa");
    private static final Path DESTINATIE = RADACINA_TEST.resolve("destinatie");

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.1")
                    .withDatabaseName("therapeutica_migrare_test")
                    .withUsername("test_user")
                    .withPassword("test_password");

    @DynamicPropertySource
    static void configureaza(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add(
                "app.storage.legacy-documents-dir",
                SURSA::toString
        );
        registry.add(
                "app.storage.documents-dir",
                DESTINATIE::toString
        );
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MigrareDocumenteService serviciu;

    private Path folderActiv;
    private Path fisierRetras;

    private byte[] primaImagine;
    private byte[] aDouaImagine;
    private byte[] imagineRetrasa;

    @BeforeEach
    void pregateste() throws Exception {
        assertEquals(
                postgres.getDatabaseName(),
                jdbc.queryForObject(
                        "SELECT current_database()",
                        String.class
                )
        );

        // Flyway rulează explicit numai pe PostgreSQL-ul temporar.
        Flyway.configure()
                .dataSource(
                        postgres.getJdbcUrl(),
                        postgres.getUsername(),
                        postgres.getPassword()
                )
                .locations("classpath:db/migration")
                .cleanDisabled(true)
                .load()
                .migrate();

        jdbc.execute("""
                DROP TRIGGER IF EXISTS refuza_fisier_migrare_test
                ON public.fisiere_documente_medicale
                """);

        jdbc.execute("""
                DROP FUNCTION IF EXISTS
                public.refuza_fisier_migrare_test()
                """);

        jdbc.execute("TRUNCATE TABLE public.utilizatori CASCADE");

        FileSystemUtils.deleteRecursively(RADACINA_TEST);
        Files.createDirectories(SURSA);

        primaImagine = imaginePng(0xFFFF0000);
        aDouaImagine = imaginePng(0xFF0000FF);
        imagineRetrasa = imaginePng(0xFF00FF00);

        folderActiv = Files.createDirectory(SURSA.resolve("activ"));

        // Ordinea creării este intenționat inversată.
        Files.write(
                folderActiv.resolve("0002_a_doua.png"),
                aDouaImagine
        );
        Files.write(
                folderActiv.resolve("0001_prima.png"),
                primaImagine
        );

        fisierRetras = SURSA.resolve("retras.png");
        Files.write(fisierRetras, imagineRetrasa);

        UUID medic = UUID.randomUUID();
        UUID pacientUser = UUID.randomUUID();
        UUID pacient = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO public.utilizatori
                    (id, created_at, email, nume, prenume, rol, parola)
                VALUES
                    (?, CURRENT_TIMESTAMP, 'medic-migrare@example.test',
                     'Medic', 'Test', 'MEDIC', 'fictiva'),
                    (?, CURRENT_TIMESTAMP, 'pacient-migrare@example.test',
                     'Pacient', 'Test', 'PACIENT', 'fictiva')
                """, medic, pacientUser);

        jdbc.update("""
                INSERT INTO public.medici (user_id, specializare)
                VALUES (?, 'Test')
                """, medic);

        jdbc.update("""
                INSERT INTO public.pacienti
                    (id, user_id, medic_id, sex)
                VALUES (?, ?, ?, 'NEPRECIZAT')
                """, pacient, pacientUser, medic);

        jdbc.update("""
                INSERT INTO public.documente_medicale
                    (id, pacient_id, nume_fisier, cale_fisier_stocare,
                     data_incarcare, status, version)
                VALUES
                    (?, ?, 'Document activ', ?,
                     CURRENT_TIMESTAMP, 'INCARCAT', 0),
                    (?, ?, 'Document retras', ?,
                     CURRENT_TIMESTAMP, 'STERS_DE_PACIENT', 0)
                """,
                DOCUMENT_ACTIV, pacient, folderActiv.toString(),
                DOCUMENT_RETRAS, pacient, fisierRetras.toString());
    }

    @AfterAll
    static void curata() throws IOException {
        FileSystemUtils.deleteRecursively(RADACINA_TEST);
    }

    @Test
    void migrareaPastreazaOriginaleleSiRepetareaNuCreeazaDuplicate()
            throws Exception {

        var verificare = serviciu.migreaza(
                postgres.getDatabaseName(),
                false
        );

        assertEquals(2, verificare.documenteNoi());
        assertEquals(3, verificare.fisiereNoi());
        assertEquals(0L, numarInregistrariFisiere());
        assertEquals(0L, numarFisiere(DESTINATIE));
        verificaCaiInitiale();

        var aplicare = serviciu.migreaza(
                postgres.getDatabaseName(),
                true
        );

        assertEquals(2, aplicare.documenteNoi());
        assertEquals(3, aplicare.fisiereNoi());
        assertEquals(3L, numarInregistrariFisiere());
        assertEquals(3L, numarFisiere(DESTINATIE));

        assertEquals(
                DOCUMENT_ACTIV.toString(),
                caleDocument(DOCUMENT_ACTIV)
        );
        assertEquals(
                DOCUMENT_RETRAS.toString(),
                caleDocument(DOCUMENT_RETRAS)
        );

        assertEquals("INCARCAT", statusDocument(DOCUMENT_ACTIV));
        assertEquals(
                "STERS_DE_PACIENT",
                statusDocument(DOCUMENT_RETRAS)
        );

        assertEquals(0L, jdbc.queryForObject("""
                SELECT count(*)
                FROM public.fisiere_documente_medicale
                WHERE nume_original IS NOT NULL
                """, Long.class));

        var evidenta = jdbc.queryForList("""
                SELECT id, document_id, cheie_stocare, nume_afisat,
                       tip_media, dimensiune_octeti, ordine, sha256
                FROM public.fisiere_documente_medicale
                ORDER BY document_id, ordine
                """);

        assertEquals(
                java.util.List.of(
                        "0001_prima.png",
                        "0002_a_doua.png",
                        "retras.png"
                ),
                evidenta.stream()
                        .map(rand -> rand.get("nume_afisat").toString())
                        .toList()
        );

        byte[][] originale = {
                primaImagine,
                aDouaImagine,
                imagineRetrasa
        };

        for (int index = 0; index < originale.length; index++) {
            var rand = evidenta.get(index);
            String cheie = rand.get("cheie_stocare").toString();

            assertFalse(Path.of(cheie).isAbsolute());
            assertArrayEquals(
                    originale[index],
                    Files.readAllBytes(DESTINATIE.resolve(cheie))
            );
            assertEquals(
                    HexFormat.of().formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(originale[index])
                    ),
                    rand.get("sha256")
            );
        }

        var repetare = serviciu.migreaza(
                postgres.getDatabaseName(),
                true
        );

        assertEquals(0, repetare.documenteNoi());
        assertEquals(0, repetare.fisiereNoi());
        assertEquals(2, repetare.documenteExistente());
        assertEquals(3, repetare.fisiereExistente());

        assertEquals(evidenta, jdbc.queryForList("""
                SELECT id, document_id, cheie_stocare, nume_afisat,
                       tip_media, dimensiune_octeti, ordine, sha256
                FROM public.fisiere_documente_medicale
                ORDER BY document_id, ordine
                """));

        assertEquals(3L, numarFisiere(DESTINATIE));
        verificaOriginale();
    }

    @Test
    void eroareaSqlLaUltimulFisierAnuleazaIntreagaMigrare()
            throws Exception {

        jdbc.execute("""
                CREATE FUNCTION public.refuza_fisier_migrare_test()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $functie$
                BEGIN
                    IF NEW.document_id = '%s'::uuid THEN
                        RAISE EXCEPTION
                            'Eroare SQL simulata exclusiv in test.';
                    END IF;

                    RETURN NEW;
                END;
                $functie$;
                """.formatted(DOCUMENT_RETRAS));

        jdbc.execute("""
                CREATE TRIGGER refuza_fisier_migrare_test
                BEFORE INSERT ON public.fisiere_documente_medicale
                FOR EACH ROW
                EXECUTE FUNCTION public.refuza_fisier_migrare_test()
                """);

        RuntimeException eroare = assertThrows(
                RuntimeException.class,
                () -> serviciu.migreaza(
                        postgres.getDatabaseName(),
                        true
                )
        );

        Throwable cauza = eroare;

        while (cauza.getCause() != null) {
            cauza = cauza.getCause();
        }

        assertTrue(cauza.getMessage().contains(
                "Eroare SQL simulata exclusiv in test."
        ));

        assertEquals(0L, numarInregistrariFisiere());
        assertEquals(0L, numarFisiere(DESTINATIE));

        if (Files.exists(DESTINATIE)) {
            try (var continut = Files.list(DESTINATIE)) {
                assertEquals(0L, continut.count());
            }
        }

        verificaCaiInitiale();
        verificaOriginale();

        assertEquals("INCARCAT", statusDocument(DOCUMENT_ACTIV));
        assertEquals(
                "STERS_DE_PACIENT",
                statusDocument(DOCUMENT_RETRAS)
        );
    }

    private void verificaCaiInitiale() {
        assertEquals(
                folderActiv.toString(),
                caleDocument(DOCUMENT_ACTIV)
        );
        assertEquals(
                fisierRetras.toString(),
                caleDocument(DOCUMENT_RETRAS)
        );
    }

    private void verificaOriginale() throws IOException {
        assertEquals(3L, numarFisiere(SURSA));

        assertArrayEquals(
                primaImagine,
                Files.readAllBytes(
                        folderActiv.resolve("0001_prima.png")
                )
        );
        assertArrayEquals(
                aDouaImagine,
                Files.readAllBytes(
                        folderActiv.resolve("0002_a_doua.png")
                )
        );
        assertArrayEquals(
                imagineRetrasa,
                Files.readAllBytes(fisierRetras)
        );
    }

    private long numarInregistrariFisiere() {
        return jdbc.queryForObject("""
                SELECT count(*)
                FROM public.fisiere_documente_medicale
                """, Long.class);
    }

    private long numarFisiere(Path director) throws IOException {
        if (!Files.exists(director)) {
            return 0;
        }

        try (var continut = Files.walk(director)) {
            return continut
                    .filter(path -> Files.isRegularFile(path))
                    .count();
        }
    }

    private String caleDocument(UUID documentId) {
        return jdbc.queryForObject("""
                SELECT cale_fisier_stocare
                FROM public.documente_medicale
                WHERE id = ?
                """, String.class, documentId);
    }

    private String statusDocument(UUID documentId) {
        return jdbc.queryForObject("""
                SELECT status
                FROM public.documente_medicale
                WHERE id = ?
                """, String.class, documentId);
    }

    private byte[] imaginePng(int culoare) throws IOException {
        BufferedImage imagine = new BufferedImage(
                1, 1, BufferedImage.TYPE_INT_ARGB
        );
        imagine.setRGB(0, 0, culoare);

        try (var iesire = new ByteArrayOutputStream()) {
            if (!ImageIO.write(imagine, "png", iesire)) {
                throw new IOException(
                        "Imaginea de test nu poate fi generată."
                );
            }

            return iesire.toByteArray();
        }
    }
}