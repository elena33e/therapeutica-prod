package com.therapeutica.therapeutica_app.infrastructure;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class OperatiuniBazaDateTest {

    private static final String ROL_APLICATIE =
            "therapeutica_aplicatie_user";

    private static final String ROL_MIGRARI =
            "therapeutica_migrari_user";

    // Exclusiv pentru PostgreSQL-ul temporar al testelor.
    private static final String PAROLA_APLICATIE =
            "parola_fictiva_aplicatie_test";

    private static final String PAROLA_MIGRARI =
            "parola_fictiva_migrari_test";

    private static final String OPERATIUNI =
            "/tmp/therapeutica/scripts/db/";

    private static final String MIGRARI =
            "/tmp/therapeutica/therapeutica-app/"
                    + "src/main/resources/db/migration/";

    private static final String PREGATIRE =
            "pregatire-adoptare-v3.sql";

    private static final String ROLURI =
            "configurare-roluri-v3.sql";

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18.1")
                    .withDatabaseName("therapeutica_operatiuni_test")
                    .withUsername("test_admin")
                    .withPassword("parola_fictiva_admin_test");

    @BeforeAll
    static void pregatesteScripturile() throws Exception {
        reusit(postgres.execInContainer(
                "mkdir", "-p", OPERATIUNI, MIGRARI
        ));

        Path radacina = Path.of("").toAbsolutePath().normalize();

        while (radacina != null
                && !Files.isRegularFile(
                radacina.resolve("scripts/db/" + PREGATIRE)
        )) {
            radacina = radacina.getParent();
        }

        assertNotNull(
                radacina,
                "Nu a fost găsit directorul scripts/db din repository."
        );

        for (String script : List.of(PREGATIRE, ROLURI)) {
            Path sursa = radacina.resolve("scripts/db/" + script);
            assertTrue(Files.isRegularFile(sursa), sursa.toString());

            postgres.copyFileToContainer(
                    MountableFile.forHostPath(sursa),
                    OPERATIUNI + script
            );
        }

        for (String migrare : List.of(
                "V1__schema_initiala.sql",
                "V2__eliminare_functionalitati_disertatie.sql",
                "V3__evidenta_fisierelor_documentelor.sql"
        )) {
            postgres.copyFileToContainer(
                    MountableFile.forClasspathResource(
                            "db/migration/" + migrare
                    ),
                    MIGRARI + migrare
            );
        }

        executa(
                postgres.getDatabaseName(),
                "CREATE ROLE therapeutica_productie_user NOLOGIN"
        );
    }

    @Test
    void adoptaSchemaPastreazaDateleSiSeparaDrepturile(
            @TempDir Path migrariViitoare
    ) throws Exception {
        String baza = schemaIstorica();

        reusit(script(baza, PREGATIRE, baza));

        assertEquals(
                "INCARCAT",
                scalar(baza, """
                        SELECT status
                        FROM public.documente_medicale
                        WHERE nume_fisier = 'activ.pdf'
                        """)
        );

        assertEquals(
                "1",
                scalar(baza, """
                        SELECT count(*)
                        FROM public.documente_medicale
                        WHERE status = 'STERS_DE_PACIENT'
                        """)
        );

        assertEquals("2", scalar(
                baza,
                "SELECT count(*) FROM public.fisiere_documente_medicale"
        ));

        assertEquals("Diagnostic fictiv", scalar(
                baza,
                "SELECT diagnostic_final FROM public.diagnostic_medic"
        ));

        assertEquals("1", scalar(
                baza,
                "SELECT count(*) FROM public.diagnostic_analize_ref"
        ));

        assertEquals("1", scalar(
                baza,
                "SELECT count(*) FROM public.diagnostic_chestionare_ref"
        ));

        assertEquals("2", scalar(
                baza,
                "SELECT scor FROM public.raspunsuri_intrebari"
        ));

        assertNull(scalar(
                baza,
                "SELECT to_regclass('public.istoric_diagnoze')"
        ));

        assertEquals("0", scalar(baza, """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND (
                      (table_name = 'documente_medicale'
                       AND column_name = 'date_brute')
                      OR
                      (table_name = 'intrebari'
                       AND column_name = 'hpo_code')
                  )
                """));

        Flyway adoptare = flyway(
                baza,
                postgres.getUsername(),
                postgres.getPassword(),
                "classpath:db/migration"
        );

        adoptare.baseline();
        adoptare.validate();

        assertEquals(
                "3",
                adoptare.info().current().getVersion().toString()
        );
        assertEquals(0, adoptare.migrate().migrationsExecuted);

        reusit(script(baza, ROLURI, baza));

        executa(baza, """
                ALTER ROLE therapeutica_aplicatie_user
                    PASSWORD 'parola_fictiva_aplicatie_test';

                ALTER ROLE therapeutica_migrari_user
                    PASSWORD 'parola_fictiva_migrari_test';
                """);

        // Reluarea configurării păstrează conturile și parolele.
        reusit(script(baza, ROLURI, baza));

        try (
                Connection connection = conexiune(
                        baza, ROL_APLICATIE, PAROLA_APLICATIE
                );
                var statement = connection.createStatement()
        ) {
            assertEquals(1, statement.executeUpdate("""
                    UPDATE public.documente_medicale
                    SET nume_fisier = 'actualizat.pdf'
                    WHERE status = 'INCARCAT'
                    """));

            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO public.audit_log
                        (id, actiune, data_ora, tip_resursa)
                    VALUES (
                        gen_random_uuid(),
                        'VIZUALIZARE',
                        CURRENT_TIMESTAMP,
                        'DOCUMENT_MEDICAL'
                    )
                    """));

            assertEquals(1, statement.executeUpdate("""
                    INSERT INTO public.fisiere_documente_medicale (
                        id, document_id, cheie_stocare,
                        nume_afisat, tip_media,
                        dimensiune_octeti, ordine, sha256
                    )
                    SELECT
                        gen_random_uuid(),
                        d.id,
                        d.id::text || '/' || gen_random_uuid()::text,
                        'nou.pdf',
                        'application/pdf',
                        9,
                        2,
                        repeat('b', 64)
                    FROM public.documente_medicale d
                    WHERE d.status = 'INCARCAT'
                    """));

            for (String sql : List.of(
                    "CREATE TABLE public.interzis_test (id integer)",
                    "CREATE TEMP TABLE interzis_temporar (id integer)",
                    "SELECT * FROM public.flyway_schema_history",
                    "SET ROLE therapeutica_migrari_user",
                    "UPDATE public.intrebari "
                            + "SET text_intrebare = text_intrebare WHERE false",
                    "UPDATE public.audit_log "
                            + "SET actiune = actiune WHERE false",
                    "UPDATE public.fisiere_documente_medicale "
                            + "SET sha256 = sha256 WHERE false",
                    "DELETE FROM public.documente_medicale WHERE false"
            )) {
                SQLException eroare = assertThrows(
                        SQLException.class,
                        () -> statement.execute(sql),
                        sql
                );

                assertEquals("42501", eroare.getSQLState(), sql);
            }
        }

        Files.writeString(
                migrariViitoare.resolve("V4__proba_migrari.sql"),
                """
                CREATE TABLE public.proba_migrari (
                    id integer PRIMARY KEY
                );
                """
        );

        Flyway actualizare = flyway(
                baza,
                ROL_MIGRARI,
                PAROLA_MIGRARI,
                "classpath:db/migration",
                "filesystem:" + migrariViitoare
        );

        assertEquals(1, actualizare.migrate().migrationsExecuted);
        actualizare.validate();
        assertEquals(0, actualizare.migrate().migrationsExecuted);

        assertEquals(ROL_MIGRARI, scalar(baza, """
                SELECT tableowner
                FROM pg_tables
                WHERE schemaname = 'public'
                  AND tablename = 'proba_migrari'
                """));

        // Tabelele viitoare nu devin automat accesibile aplicației.
        try (
                Connection connection = conexiune(
                        baza, ROL_APLICATIE, PAROLA_APLICATIE
                );
                var statement = connection.createStatement()
        ) {
            SQLException eroare = assertThrows(
                    SQLException.class,
                    () -> statement.executeQuery(
                            "SELECT * FROM public.proba_migrari"
                    )
            );

            assertEquals("42501", eroare.getSQLState());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "pregatire-adoptare-v3.sql",
            "configurare-roluri-v3.sql"
    })
    void scriptulRefuzaAltaBazaFaraModificari(String numeScript)
            throws Exception {
        String baza = schemaIstorica();

        ExecResult rezultat = script(
                baza, numeScript, "alta_baza_asteptata"
        );

        assertEquals(3, rezultat.getExitCode());

        String mesajAsteptat = numeScript.equals(PREGATIRE)
                ? "Baza conectată este"
                : "Conectare la baza greșită";

        assertTrue(
                rezultat.getStderr().contains(mesajAsteptat),
                rezultat.getStderr()
        );

        verificaSchemaIstorica(baza, "INTERPRETAT");

        assertEquals(
                "therapeutica_productie_user",
                scalar(baza, """
                        SELECT pg_get_userbyid(datdba)
                        FROM pg_database
                        WHERE datname = current_database()
                        """)
        );
    }

    @Test
    void statusulNeacceptatAnuleazaPregatireaAdoptarii()
            throws Exception {
        String baza = schemaIstorica();

        executa(baza, """
                UPDATE public.documente_medicale
                SET status = 'EROARE'
                WHERE nume_fisier = 'activ.pdf'
                """);

        ExecResult rezultat = script(baza, PREGATIRE, baza);

        assertEquals(3, rezultat.getExitCode());
        assertTrue(
                rezultat.getStderr().contains(
                        "Migrarea necesită verificarea lor."
                ),
                rezultat.getStderr()
        );

        verificaSchemaIstorica(baza, "EROARE");
    }

    private static String schemaIstorica() throws Exception {
        String baza = "operatiuni_"
                + UUID.randomUUID().toString().replace("-", "");

        executa(
                postgres.getDatabaseName(),
                "CREATE DATABASE " + baza
                        + " OWNER therapeutica_productie_user"
        );

        reusit(psql(
                baza,
                "--command=SET ROLE therapeutica_productie_user",
                "--file=" + MIGRARI + "V1__schema_initiala.sql",
                "--file=" + MIGRARI
                        + "V3__evidenta_fisierelor_documentelor.sql"
        ));

        executa(baza, """
                DO $date_test$
                DECLARE
                    medic uuid := gen_random_uuid();
                    utilizator_pacient uuid := gen_random_uuid();
                    pacient uuid := gen_random_uuid();
                    activ uuid := gen_random_uuid();
                    retras uuid := gen_random_uuid();
                    chestionar uuid := gen_random_uuid();
                    categorie uuid := gen_random_uuid();
                    intrebare uuid := gen_random_uuid();
                    atribuire uuid := gen_random_uuid();
                    diagnostic uuid := gen_random_uuid();
                BEGIN
                    INSERT INTO public.utilizatori
                        (id, created_at, email, nume, prenume, rol, parola)
                    VALUES
                        (medic, now(), 'medic@example.test',
                         'Test', 'Medic', 'MEDIC', 'fictiv'),
                        (utilizator_pacient, now(), 'pacient@example.test',
                         'Test', 'Pacient', 'PACIENT', 'fictiv');

                    INSERT INTO public.medici (user_id, specializare)
                    VALUES (medic, 'Test');

                    INSERT INTO public.pacienti (id, user_id, medic_id)
                    VALUES (pacient, utilizator_pacient, medic);

                    INSERT INTO public.documente_medicale (
                        id, pacient_id, cale_fisier_stocare,
                        nume_fisier, status, version, date_brute
                    )
                    VALUES
                        (activ, pacient, activ::text, 'activ.pdf',
                         'INTERPRETAT', 7, '{"test": true}'::jsonb),
                        (retras, pacient, retras::text, 'retras.pdf',
                         'STERS_DE_PACIENT', 7, NULL);

                    INSERT INTO public.fisiere_documente_medicale (
                        id, document_id, cheie_stocare,
                        nume_afisat, tip_media,
                        dimensiune_octeti, ordine, sha256
                    )
                    SELECT
                        gen_random_uuid(), d.id,
                        d.id::text || '/' || gen_random_uuid()::text,
                        d.nume_fisier, 'application/pdf',
                        8, 1, repeat('a', 64)
                    FROM public.documente_medicale d;

                    INSERT INTO public.chestionare (id, nume)
                    VALUES (chestionar, 'Chestionar fictiv');

                    INSERT INTO public.categorii_chestionare
                        (id, chestionar_id, nume)
                    VALUES (categorie, chestionar, 'Categorie fictiva');

                    INSERT INTO public.intrebari
                        (id, categorie_id, text_intrebare, hpo_code)
                    VALUES
                        (intrebare, categorie, 'Intrebare fictiva', 'HP:TEST');

                    INSERT INTO public.raspunsuri_chestionare
                        (id, pacient_id, medic_id, chestionar_id)
                    VALUES (atribuire, pacient, medic, chestionar);

                    INSERT INTO public.raspunsuri_intrebari
                        (raspuns_chestionar_id, intrebare_id, categorie_id, scor)
                    VALUES (atribuire, intrebare, categorie, 2);

                    INSERT INTO public.diagnostic_medic
                        (id, diagnostic_final, pacient_id)
                    VALUES (diagnostic, 'Diagnostic fictiv', pacient);

                    INSERT INTO public.diagnostic_analize_ref
                        (diagnostic_id, analiza_id)
                    VALUES (diagnostic, activ);

                    INSERT INTO public.diagnostic_chestionare_ref
                        (diagnostic_id, chestionar_id)
                    VALUES (diagnostic, atribuire);

                    INSERT INTO public.istoric_diagnoze
                        (id, data_rulare, rezultat_json, pacient_id)
                    VALUES
                        (gen_random_uuid(), now(), '{"test": true}', pacient);
                END
                $date_test$;
                """);

        return baza;
    }

    private static void verificaSchemaIstorica(
            String baza, String status
    ) throws Exception {
        assertEquals(status, scalar(baza, """
                SELECT status
                FROM public.documente_medicale
                WHERE nume_fisier = 'activ.pdf'
                """));

        assertEquals("true", scalar(baza, """
                SELECT date_brute IS NOT NULL
                FROM public.documente_medicale
                WHERE nume_fisier = 'activ.pdf'
                """));

        assertEquals("1", scalar(
                baza,
                "SELECT count(*) FROM public.istoric_diagnoze"
        ));

        assertEquals("2", scalar(
                baza,
                "SELECT count(*) FROM public.fisiere_documente_medicale"
        ));

        assertNull(scalar(
                baza,
                "SELECT to_regclass('public.flyway_schema_history')"
        ));
    }

    private static ExecResult script(
            String baza, String numeScript, String bazaAsteptata
    ) throws Exception {
        return psql(
                baza,
                "--set=baza_asteptata=" + bazaAsteptata,
                "--file=" + OPERATIUNI + numeScript
        );
    }

    private static ExecResult psql(
            String baza, String... optiuni
    ) throws Exception {
        List<String> argumente = new ArrayList<>(List.of(
                "psql",
                "--username=" + postgres.getUsername(),
                "--dbname=" + baza,
                "--no-psqlrc",
                "--set=ON_ERROR_STOP=1",
                "--pset=pager=off"
        ));

        argumente.addAll(Arrays.asList(optiuni));

        return postgres.execInContainer(
                argumente.toArray(String[]::new)
        );
    }

    private static Flyway flyway(
            String baza, String utilizator,
            String parola, String... locatii
    ) {
        return Flyway.configure()
                .dataSource(url(baza), utilizator, parola)
                .locations(locatii)
                .defaultSchema("public")
                .baselineVersion("3")
                .baselineOnMigrate(false)
                .cleanDisabled(true)
                .load();
    }

    private static String url(String baza) {
        return postgres.getJdbcUrl().replace(
                "/" + postgres.getDatabaseName(),
                "/" + baza
        );
    }

    private static Connection conexiune(
            String baza, String utilizator, String parola
    ) throws SQLException {
        return DriverManager.getConnection(
                url(baza), utilizator, parola
        );
    }

    private static void executa(String baza, String sql)
            throws Exception {
        try (
                Connection connection = conexiune(
                        baza,
                        postgres.getUsername(),
                        postgres.getPassword()
                );
                var statement = connection.createStatement()
        ) {
            statement.execute(sql);
        }
    }

    private static String scalar(String baza, String sql)
            throws Exception {
        try (
                Connection connection = conexiune(
                        baza,
                        postgres.getUsername(),
                        postgres.getPassword()
                );
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)
        ) {
            assertTrue(result.next());

            Object valoare = result.getObject(1);
            return valoare == null ? null : valoare.toString();
        }
    }

    private static void reusit(ExecResult rezultat) {
        assertEquals(
                0,
                rezultat.getExitCode(),
                rezultat.getStdout() + "\n" + rezultat.getStderr()
        );
    }
}