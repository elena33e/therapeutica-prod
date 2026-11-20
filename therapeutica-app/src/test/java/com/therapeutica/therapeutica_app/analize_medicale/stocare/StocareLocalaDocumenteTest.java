package com.therapeutica.therapeutica_app.analize_medicale.stocare;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StocareLocalaDocumenteTest {

    private static final byte[] CONTINUT =
            "abc".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path temporar;

    private Path radacina;
    private StocareLocalaDocumente stocare;

    @BeforeEach
    void configureaza() {
        radacina = temporar.resolve("documente");
        stocare = new StocareLocalaDocumente(radacina.toString());
    }

    @Test
    void pastreazaOctetiiSiCalculeazaDimensiuneaSiHashul()
            throws IOException {

        UUID documentId = UUID.randomUUID();
        UUID fisierId = UUID.randomUUID();

        var rezultat = stocare.salveaza(
                documentId,
                fisierId,
                new ByteArrayInputStream(CONTINUT),
                CONTINUT.length
        );

        assertEquals(
                documentId + "/" + fisierId,
                rezultat.cheieStocare()
        );
        assertEquals(3L, rezultat.dimensiuneOcteti());
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223"
                        + "b00361a396177a9cb410ff61f20015ad",
                rezultat.sha256()
        );

        try (InputStream intrare =
                     stocare.deschide(rezultat.cheieStocare())) {

            assertArrayEquals(CONTINUT, intrare.readAllBytes());
        }
    }

    @Test
    void refuzaSuprascriereaSiPastreazaFisierulExistent()
            throws IOException {

        UUID documentId = UUID.randomUUID();
        UUID fisierId = UUID.randomUUID();

        var rezultat = stocare.salveaza(
                documentId,
                fisierId,
                new ByteArrayInputStream(CONTINUT),
                100
        );

        assertThrows(
                IOException.class,
                () -> stocare.salveaza(
                        documentId,
                        fisierId,
                        new ByteArrayInputStream(new byte[]{9, 8, 7}),
                        100
                )
        );

        try (InputStream intrare =
                     stocare.deschide(rezultat.cheieStocare())) {

            assertArrayEquals(CONTINUT, intrare.readAllBytes());
        }
    }

    @Test
    void depasireaLimiteiEliminaFisierulPartial()
            throws IOException {

        UUID documentId = UUID.randomUUID();
        UUID fisierId = UUID.randomUUID();

        // Primul bloc încape; următorul octet depășește limita.
        byte[] preaMare = new byte[8193];

        assertThrows(
                IllegalArgumentException.class,
                () -> stocare.salveaza(
                        documentId,
                        fisierId,
                        new ByteArrayInputStream(preaMare),
                        8192
                )
        );

        Path destinatie = radacina
                .resolve(documentId.toString())
                .resolve(fisierId.toString());

        assertFalse(Files.exists(destinatie));

        // Aceeași cheie poate fi folosită după curățarea scrierii eșuate.
        var rezultat = stocare.salveaza(
                documentId,
                fisierId,
                new ByteArrayInputStream(CONTINUT),
                8192
        );

        assertEquals(3L, rezultat.dimensiuneOcteti());
    }

    @Test
    void eroareaDeCitireEliminaFisierulPartial() {
        UUID documentId = UUID.randomUUID();
        UUID fisierId = UUID.randomUUID();

        InputStream sursaDefecta = new InputStream() {
            private int cititi;

            @Override
            public int read() throws IOException {
                if (cititi++ < 3) {
                    return 'x';
                }

                throw new IOException("Eroare simulată de citire.");
            }
        };

        assertThrows(
                IOException.class,
                () -> stocare.salveaza(
                        documentId,
                        fisierId,
                        sursaDefecta,
                        100
                )
        );

        assertFalse(Files.exists(
                radacina
                        .resolve(documentId.toString())
                        .resolve(fisierId.toString())
        ));
    }

    @Test
    void refuzaFisierulGolSiLimitaNepozitiva() {
        UUID documentId = UUID.randomUUID();
        UUID fisierId = UUID.randomUUID();

        assertThrows(
                IllegalArgumentException.class,
                () -> stocare.salveaza(
                        documentId,
                        fisierId,
                        new ByteArrayInputStream(new byte[0]),
                        100
                )
        );

        assertFalse(Files.exists(
                radacina
                        .resolve(documentId.toString())
                        .resolve(fisierId.toString())
        ));

        assertThrows(
                IllegalArgumentException.class,
                () -> stocare.salveaza(
                        documentId,
                        fisierId,
                        new ByteArrayInputStream(CONTINUT),
                        0
                )
        );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "../secret.pdf",
            "document/../secret.pdf",
            "document/./fisier",
            "/etc/passwd",
            "C:/date/fisier.pdf",
            "C:\\date\\fisier.pdf",
            "document//fisier",
            "document/fisier cu spatii.pdf",
            "https://exemplu.invalid/fisier"
    })
    void refuzaCheileNepermiseLaCitireSiStergere(String cheie) {
        assertThrows(
                IllegalArgumentException.class,
                () -> stocare.deschide(cheie)
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> stocare.sterge(cheie)
        );
    }

    @EnabledOnOs(OS.LINUX)
    @ParameterizedTest
    @ValueSource(strings = {"radacina", "director", "fisier"})
    void refuzaLegaturileFaraAModificaFisierulExterior(String tip)
            throws IOException {

        UUID documentId = UUID.randomUUID();
        UUID fisierId = UUID.randomUUID();
        String cheie = documentId + "/" + fisierId;

        Path exterior = temporar.resolve("exterior");
        Path directorExterior =
                exterior.resolve(documentId.toString());

        Files.createDirectories(directorExterior);

        Path fisierExterior =
                directorExterior.resolve(fisierId.toString());

        Files.write(fisierExterior, CONTINUT);

        Path directorLocal =
                radacina.resolve(documentId.toString());

        switch (tip) {
            case "radacina" ->
                    Files.createSymbolicLink(radacina, exterior);

            case "director" -> {
                Files.createDirectories(radacina);
                Files.createSymbolicLink(
                        directorLocal,
                        directorExterior
                );
            }

            case "fisier" -> {
                Files.createDirectories(directorLocal);
                Files.createSymbolicLink(
                        directorLocal.resolve(fisierId.toString()),
                        fisierExterior
                );
            }

            default -> throw new IllegalArgumentException(tip);
        }

        assertThrows(
                IOException.class,
                () -> stocare.salveaza(
                        documentId,
                        fisierId,
                        new ByteArrayInputStream(new byte[]{9}),
                        100
                )
        );

        assertThrows(
                IOException.class,
                () -> stocare.deschide(cheie)
        );

        assertThrows(
                IOException.class,
                () -> stocare.sterge(cheie)
        );

        assertArrayEquals(
                CONTINUT,
                Files.readAllBytes(fisierExterior)
        );
    }

    @Test
    void aceeasiCheieFunctioneazaDupaMutareaRadacinii()
            throws IOException {

        var rezultat = stocare.salveaza(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ByteArrayInputStream(CONTINUT),
                100
        );

        Path radacinaNoua = temporar.resolve("documente-mutate");
        Files.move(radacina, radacinaNoua);

        StocareDocumente stocareNoua =
                new StocareLocalaDocumente(radacinaNoua.toString());

        try (InputStream intrare =
                     stocareNoua.deschide(rezultat.cheieStocare())) {

            assertArrayEquals(CONTINUT, intrare.readAllBytes());
        }
    }

    @Test
    void stergereaEsteIdempotenta() throws IOException {
        var rezultat = stocare.salveaza(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new ByteArrayInputStream(CONTINUT),
                100
        );

        assertTrue(stocare.sterge(rezultat.cheieStocare()));
        assertFalse(stocare.sterge(rezultat.cheieStocare()));

        assertThrows(
                IOException.class,
                () -> stocare.deschide(rezultat.cheieStocare())
        );
    }

    @Test
    void citireaSiStergereaNuCreeazaDirectoare() throws IOException {
        String cheie = UUID.randomUUID() + "/" + UUID.randomUUID();

        assertThrows(
                IOException.class,
                () -> stocare.deschide(cheie)
        );

        assertFalse(stocare.sterge(cheie));
        assertFalse(Files.exists(radacina));
    }

    @Test
    void configuratiaRefuzaCaleaRelativa() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new StocareLocalaDocumente("documente")
        );
    }
}