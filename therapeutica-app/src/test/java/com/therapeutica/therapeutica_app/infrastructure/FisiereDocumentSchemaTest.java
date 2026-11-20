package com.therapeutica.therapeutica_app.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class FisiereDocumentSchemaTest {

    private static final String HASH = "a".repeat(64);

    private static final String MIGRARE =
            "db/migration/V3__evidenta_fisierelor_documentelor.sql";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.1")
                    .withDatabaseName("therapeutica_fisiere_test")
                    .withUsername("test_user")
                    .withPassword("test_password");

    private record DateTest(
            UUID document,
            UUID retras,
            String caleInitiala
    ) {}

    @Test
    void migrareaAdaugaEvidentaSiImpuneConstrangerile()
            throws Exception {

        String url = postgres.getJdbcUrl();

        flyway(url, "2").migrate();

        try (Connection connection = conexiune(url)) {
            DateTest date = creeazaDocumente(connection, "INCARCAT");

            assertEquals(
                    1,
                    flyway(url, "3").migrate().migrationsExecuted
            );

            verificaDocument(
                    connection,
                    date.document(),
                    "INCARCAT",
                    date.caleInitiala()
            );

            verificaDocument(
                    connection,
                    date.retras(),
                    "STERS_DE_PACIENT",
                    "C:/date-test/retras"
            );

            String primaCheie =
                    date.document() + "/" + UUID.randomUUID() + ".pdf";

            insereazaFisier(
                    connection, date.document(), primaCheie,
                    1, 100, "application/pdf", HASH
            );

            // Hashul identic este permis pentru alt fișier.
            insereazaFisier(
                    connection,
                    date.document(),
                    date.document() + "/" + UUID.randomUUID() + ".png",
                    2, 200, "image/png", HASH
            );

            // Un document retras își poate păstra evidența fișierelor.
            insereazaFisier(
                    connection,
                    date.retras(),
                    date.retras() + "/" + UUID.randomUUID() + ".pdf",
                    1, 100, "application/pdf", HASH
            );

            // Aceeași cheie nu poate aparține altui fișier.
            SQLException cheieDuplicata = assertThrows(
                    SQLException.class,
                    () -> insereazaFisier(
                            connection, date.retras(), primaCheie,
                            2, 100, "application/pdf", HASH
                    )
            );
            assertEquals("23505", cheieDuplicata.getSQLState());

            // Poziția este unică în interiorul documentului.
            SQLException ordineDuplicata = assertThrows(
                    SQLException.class,
                    () -> insereazaFisier(
                            connection,
                            date.document(),
                            date.document() + "/" + UUID.randomUUID() + ".pdf",
                            1, 100, "application/pdf", HASH
                    )
            );
            assertEquals("23505", ordineDuplicata.getSQLState());

            for (String cheieInvalida : List.of(
                    "C:/date/fisier.pdf",
                    "C:\\date\\fisier.pdf",
                    "/date/fisier.pdf",
                    "../fisier.pdf",
                    "document/../fisier.pdf",
                    "document/./fisier.pdf",
                    "document//fisier.pdf",
                    "document/fisier cu spatii.pdf",
                    "https://exemplu.test/fisier.pdf"
            )) {
                SQLException eroare = assertThrows(
                        SQLException.class,
                        () -> insereazaFisier(
                                connection,
                                date.document(),
                                cheieInvalida,
                                3, 100, "application/pdf", HASH
                        )
                );

                assertEquals(
                        "23514",
                        eroare.getSQLState(),
                        "Cheia trebuia refuzată: " + cheieInvalida
                );
            }

            String cheieValida =
                    date.document() + "/" + UUID.randomUUID() + ".pdf";

            assertEquals(
                    "23514",
                    assertThrows(SQLException.class, () ->
                            insereazaFisier(
                                    connection,
                                    date.document(),
                                    cheieValida,
                                    0, 100, "application/pdf", HASH
                            )
                    ).getSQLState()
            );

            assertEquals(
                    "23514",
                    assertThrows(SQLException.class, () ->
                            insereazaFisier(
                                    connection,
                                    date.document(),
                                    cheieValida,
                                    3, 0, "application/pdf", HASH
                            )
                    ).getSQLState()
            );

            assertEquals(
                    "23514",
                    assertThrows(SQLException.class, () ->
                            insereazaFisier(
                                    connection,
                                    date.document(),
                                    cheieValida,
                                    3, 100, "text/html", HASH
                            )
                    ).getSQLState()
            );

            assertEquals(
                    "23514",
                    assertThrows(SQLException.class, () ->
                            insereazaFisier(
                                    connection,
                                    date.document(),
                                    cheieValida,
                                    3, 100, "application/pdf", "hash-invalid"
                            )
                    ).getSQLState()
            );

            assertEquals(
                    "23503",
                    assertThrows(SQLException.class, () ->
                            insereazaFisier(
                                    connection,
                                    UUID.randomUUID(),
                                    cheieValida,
                                    1, 100, "application/pdf", HASH
                            )
                    ).getSQLState()
            );

            SQLException stergereDocument = assertThrows(
                    SQLException.class,
                    () -> {
                        try (var statement = connection.prepareStatement("""
                                DELETE FROM public.documente_medicale
                                WHERE id = ?
                                """)) {
                            statement.setObject(1, date.document());
                            statement.executeUpdate();
                        }
                    }
            );
            assertEquals("23001", stergereDocument.getSQLState());

            // Retragerea logică rămâne permisă și păstrează fișierele.
            try (var statement = connection.prepareStatement("""
                    UPDATE public.documente_medicale
                    SET status = 'STERS_DE_PACIENT'
                    WHERE id = ?
                    """)) {
                statement.setObject(1, date.document());
                assertEquals(1, statement.executeUpdate());
            }

            try (
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT COUNT(*)
                            FROM public.fisiere_documente_medicale
                            """)
            ) {
                assertTrue(result.next());
                assertEquals(3L, result.getLong(1));
            }
        }

        Flyway flyway = flyway(url, "3");
        flyway.validate();

        assertEquals(0, flyway.migrate().migrationsExecuted);
    }

    @Test
    void tabelaPoateFiAdaugataPeSchemaIstoricaFaraRulareaV2()
            throws Exception {

        String numeBaza = "therapeutica_fisiere_legacy_test";

        try (
                Connection connection = conexiune(postgres.getJdbcUrl());
                var statement = connection.createStatement()
        ) {
            statement.execute(
                    "CREATE DATABASE therapeutica_fisiere_legacy_test"
            );
        }

        String url = postgres.getJdbcUrl().replace(
                "/" + postgres.getDatabaseName(),
                "/" + numeBaza
        );

        flyway(url, "1").migrate();

        try (Connection connection = conexiune(url)) {
            DateTest date = creeazaDocumente(
                    connection, "INTERPRETAT"
            );

            String sql;
            try (var input = new ClassPathResource(MIGRARE).getInputStream()) {
                sql = new String(
                        input.readAllBytes(),
                        StandardCharsets.UTF_8
                );
            }

            connection.setAutoCommit(false);

            try (var statement = connection.createStatement()) {
                statement.execute(sql);
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }

            verificaDocument(
                    connection,
                    date.document(),
                    "INTERPRETAT",
                    date.caleInitiala()
            );

            verificaDocument(
                    connection,
                    date.retras(),
                    "STERS_DE_PACIENT",
                    "C:/date-test/retras"
            );

            try (
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT
                                to_regclass('public.fisiere_documente_medicale'),
                                to_regclass('public.istoric_diagnoze'),
                                EXISTS (
                                    SELECT 1
                                    FROM information_schema.columns
                                    WHERE table_schema = 'public'
                                      AND table_name = 'documente_medicale'
                                      AND column_name = 'date_brute'
                                )
                            """)
            ) {
                assertTrue(result.next());
                assertNotNull(result.getString(1));
                assertNotNull(result.getString(2));
                assertTrue(result.getBoolean(3));
            }

            insereazaFisier(
                    connection,
                    date.document(),
                    date.document() + "/" + UUID.randomUUID() + ".pdf",
                    1, 100, "application/pdf", HASH
            );
        }
    }

    private Flyway flyway(String url, String versiune) {
        return Flyway.configure()
                .dataSource(
                        url,
                        postgres.getUsername(),
                        postgres.getPassword()
                )
                .locations("classpath:db/migration")
                .defaultSchema("public")
                .target(versiune)
                .cleanDisabled(true)
                .load();
    }

    private Connection conexiune(String url) throws SQLException {
        return DriverManager.getConnection(
                url,
                postgres.getUsername(),
                postgres.getPassword()
        );
    }

    private DateTest creeazaDocumente(
            Connection connection,
            String statusDocument) throws SQLException {

        UUID medic = UUID.randomUUID();
        UUID pacientUser = UUID.randomUUID();
        UUID pacient = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID retras = UUID.randomUUID();

        String cale = "C:/date-test/original";

        try (var statement = connection.prepareStatement("""
                INSERT INTO public.utilizatori
                    (id, created_at, email, nume, prenume, rol, parola)
                VALUES
                    (?, CURRENT_TIMESTAMP, 'medic-fisiere@example.test',
                     'Test', 'Medic', 'MEDIC', 'fictiva'),
                    (?, CURRENT_TIMESTAMP, 'pacient-fisiere@example.test',
                     'Test', 'Pacient', 'PACIENT', 'fictiva')
                """)) {
            statement.setObject(1, medic);
            statement.setObject(2, pacientUser);
            statement.executeUpdate();
        }

        try (var statement = connection.prepareStatement("""
                INSERT INTO public.medici (user_id, specializare)
                VALUES (?, 'Test')
                """)) {
            statement.setObject(1, medic);
            statement.executeUpdate();
        }

        try (var statement = connection.prepareStatement("""
                INSERT INTO public.pacienti (id, user_id, medic_id, sex)
                VALUES (?, ?, ?, 'NEPRECIZAT')
                """)) {
            statement.setObject(1, pacient);
            statement.setObject(2, pacientUser);
            statement.setObject(3, medic);
            statement.executeUpdate();
        }

        try (var statement = connection.prepareStatement("""
                INSERT INTO public.documente_medicale
                    (id, pacient_id, nume_fisier, cale_fisier_stocare,
                     status, version)
                VALUES
                    (?, ?, 'Document de test', ?, ?, 0),
                    (?, ?, 'Document retras', 'C:/date-test/retras',
                     'STERS_DE_PACIENT', 0)
                """)) {
            statement.setObject(1, document);
            statement.setObject(2, pacient);
            statement.setString(3, cale);
            statement.setString(4, statusDocument);
            statement.setObject(5, retras);
            statement.setObject(6, pacient);
            statement.executeUpdate();
        }

        return new DateTest(document, retras, cale);
    }

    private void insereazaFisier(
            Connection connection,
            UUID documentId,
            String cheie,
            int ordine,
            long dimensiune,
            String tipMedia,
            String hash) throws SQLException {

        try (var statement = connection.prepareStatement("""
                INSERT INTO public.fisiere_documente_medicale
                    (id, document_id, cheie_stocare,
                     nume_afisat, nume_original, tip_media,
                     dimensiune_octeti, ordine, sha256)
                VALUES (?, ?, ?, 'Fisier de test', NULL, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, documentId);
            statement.setString(3, cheie);
            statement.setString(4, tipMedia);
            statement.setLong(5, dimensiune);
            statement.setInt(6, ordine);
            statement.setString(7, hash);
            statement.executeUpdate();
        }
    }

    private void verificaDocument(
            Connection connection,
            UUID documentId,
            String status,
            String cale) throws SQLException {

        try (var statement = connection.prepareStatement("""
                SELECT status, cale_fisier_stocare
                FROM public.documente_medicale
                WHERE id = ?
                """)) {
            statement.setObject(1, documentId);

            try (var result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(status, result.getString("status"));
                assertEquals(cale, result.getString("cale_fisier_stocare"));
            }
        }
    }
}