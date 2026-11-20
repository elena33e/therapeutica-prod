package com.therapeutica.migrari;

import com.therapeutica.therapeutica_app.analize_medicale.LimiteDocumente;
import com.therapeutica.therapeutica_app.analize_medicale.ValidareFisiereDocumenteService;
import com.therapeutica.therapeutica_app.analize_medicale.stocare.StocareDocumente;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

@Service
@Slf4j
public class MigrareDocumenteService {

    private final JdbcTemplate jdbc;
    private final StocareDocumente stocare;
    private final ValidareFisiereDocumenteService validare;
    private final LimiteDocumente limite;
    private final Path radacinaVeche;
    private final Path radacinaNoua;

    public MigrareDocumenteService(
            JdbcTemplate jdbc,
            StocareDocumente stocare,
            ValidareFisiereDocumenteService validare,
            LimiteDocumente limite,
            @Value("${app.storage.legacy-documents-dir}") String directorVechi,
            @Value("${app.storage.documents-dir}") String directorNou) {

        this.jdbc = jdbc;
        this.stocare = stocare;
        this.validare = validare;
        this.limite = limite;
        this.radacinaVeche = caleAbsoluta(directorVechi);
        this.radacinaNoua = caleAbsoluta(directorNou);
    }

    public record Raport(
            int documenteNoi,
            int fisiereNoi,
            int documenteExistente,
            int fisiereExistente
    ) {
    }

    private record DocumentVechi(UUID id, String cale) {
    }

    private record FisierExistent(
            String cheie,
            long dimensiune,
            String sha256
    ) {
    }

    @Transactional(rollbackFor = Exception.class)
    public Raport migreaza(
            String bazaAsteptata,
            boolean aplica) throws IOException {

        String bazaActuala = jdbc.queryForObject(
                "SELECT current_database()",
                String.class
        );

        if (bazaAsteptata == null
                || bazaAsteptata.isBlank()
                || !bazaAsteptata.equals(bazaActuala)) {

            throw new IllegalStateException(
                    "Baza conectată nu corespunde bazei declarate pentru migrare."
            );
        }

        if (radacinaNoua.startsWith(radacinaVeche)
                || radacinaVeche.startsWith(radacinaNoua)) {

            throw new IllegalStateException(
                    "Directoarele sursă și destinație trebuie să fie separate."
            );
        }

        verificaLocatie(radacinaVeche);

        if (!Files.isDirectory(radacinaVeche, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Directorul sursă nu este disponibil."
            );
        }

        jdbc.execute("SET LOCAL lock_timeout = '5s'");
        jdbc.execute("""
                LOCK TABLE public.documente_medicale,
                           public.fisiere_documente_medicale
                IN SHARE ROW EXCLUSIVE MODE
                """);

        verificaInventarNou();

        List<DocumentVechi> documente = jdbc.query("""
                SELECT id, cale_fisier_stocare
                FROM public.documente_medicale
                ORDER BY id
                """,
                (rs, index) -> new DocumentVechi(
                        rs.getObject("id", UUID.class),
                        rs.getString("cale_fisier_stocare")
                ));

        List<String> cheiCreate = new ArrayList<>();

        if (aplica) {
            inregistreazaCuratareLaRollback(cheiCreate);
        }

        int documenteNoi = 0;
        int fisiereNoi = 0;
        int documenteExistente = 0;
        int fisiereExistente = 0;

        for (DocumentVechi document : documente) {
            List<FisierExistent> existente = jdbc.query("""
                    SELECT cheie_stocare, dimensiune_octeti, sha256
                    FROM public.fisiere_documente_medicale
                    WHERE document_id = ?
                    ORDER BY ordine
                    """,
                    (rs, index) -> new FisierExistent(
                            rs.getString("cheie_stocare"),
                            rs.getLong("dimensiune_octeti"),
                            rs.getString("sha256")
                    ),
                    document.id());

            if (!existente.isEmpty()) {
                if (!document.id().toString().equals(document.cale())) {
                    throw new IllegalStateException(
                            "Documentul " + document.id()
                                    + " are o stare de migrare neconcordantă."
                    );
                }

                for (FisierExistent fisier : existente) {
                    verificaStocat(
                            fisier.cheie(),
                            fisier.dimensiune(),
                            fisier.sha256()
                    );
                }

                documenteExistente++;
                fisiereExistente += existente.size();
                continue;
            }

            List<Path> surse = fisiereVechi(document.cale());

            if (surse.isEmpty()) {
                throw new IllegalStateException(
                        "Documentul " + document.id()
                                + " nu conține fișiere."
                );
            }

            long totalOcteti = 0;

            for (int index = 0; index < surse.size(); index++) {
                Path sursa = surse.get(index);

                ValidareFisiereDocumenteService.FisierValidat validat;

                try (var intrare = Files.newInputStream(
                        sursa,
                        StandardOpenOption.READ,
                        LinkOption.NOFOLLOW_LINKS
                )) {
                    validat = validare.valideaza(
                            sursa.getFileName().toString(),
                            intrare,
                            limite.maxOctetiDocument() - totalOcteti
                    );
                } catch (IOException | IllegalArgumentException eroare) {
                    throw new IllegalStateException(
                            "Validarea documentului " + document.id()
                                    + ", fișier " + sursa.getFileName()
                                    + ", a eșuat: " + eroare.getMessage(),
                            eroare
                    );
                }

                totalOcteti += validat.continut().length;

                if (aplica) {
                    UUID fisierId = UUID.randomUUID();

                    StocareDocumente.FisierStocat stocat;

                    try (var intrare =
                                 new ByteArrayInputStream(validat.continut())) {

                        stocat = stocare.salveaza(
                                document.id(),
                                fisierId,
                                intrare,
                                limite.maxOctetiFisier()
                        );
                    }

                    cheiCreate.add(stocat.cheieStocare());

                    verificaStocat(
                            stocat.cheieStocare(),
                            stocat.dimensiuneOcteti(),
                            stocat.sha256()
                    );

                    jdbc.update("""
                            INSERT INTO public.fisiere_documente_medicale
                                (id, document_id, cheie_stocare,
                                 nume_afisat, nume_original, tip_media,
                                 dimensiune_octeti, ordine, sha256)
                            VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?)
                            """,
                            fisierId,
                            document.id(),
                            stocat.cheieStocare(),
                            sursa.getFileName().toString(),
                            validat.tipMedia(),
                            stocat.dimensiuneOcteti(),
                            index + 1,
                            stocat.sha256());
                }

                fisiereNoi++;
            }

            if (aplica) {
                int actualizate = jdbc.update("""
                        UPDATE public.documente_medicale
                        SET cale_fisier_stocare = ?
                        WHERE id = ?
                        """,
                        document.id().toString(),
                        document.id());

                if (actualizate != 1) {
                    throw new IllegalStateException(
                            "Actualizarea documentului a eșuat."
                    );
                }
            }

            documenteNoi++;
        }

        return new Raport(
                documenteNoi,
                fisiereNoi,
                documenteExistente,
                fisiereExistente
        );
    }

    private List<Path> fisiereVechi(String cale) throws IOException {
        Path locatie = caleAbsoluta(cale);

        if (!locatie.startsWith(radacinaVeche)
                || locatie.equals(radacinaVeche)) {

            throw new IOException(
                    "Documentul indică o locație în afara sursei permise."
            );
        }

        verificaLocatie(locatie);

        if (Files.isRegularFile(locatie, LinkOption.NOFOLLOW_LINKS)) {
            return List.of(locatie);
        }

        if (!Files.isDirectory(locatie, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Locația documentului nu este permisă."
            );
        }

        List<Path> rezultat = new ArrayList<>();

        try (var continut = Files.list(locatie)) {
            for (Path copil : continut.toList()) {
                verificaLocatie(copil);

                if (Files.isRegularFile(copil, LinkOption.NOFOLLOW_LINKS)) {
                    rezultat.add(copil);
                }
            }
        }

        rezultat.sort(Comparator.comparing(
                path -> path.getFileName().toString()
        ));

        return rezultat;
    }

    private void verificaInventarNou() throws IOException {
        Set<String> asteptate = new HashSet<>(jdbc.queryForList("""
                SELECT cheie_stocare
                FROM public.fisiere_documente_medicale
                """, String.class));

        Set<String> gasite = new HashSet<>();

        if (Files.exists(radacinaNoua, LinkOption.NOFOLLOW_LINKS)) {
            verificaLocatie(radacinaNoua);

            if (!Files.isDirectory(radacinaNoua, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "Destinația nu este un director."
                );
            }

            try (var continut = Files.walk(radacinaNoua)) {
                for (Path locatie : continut.toList()) {
                    if (Files.isSymbolicLink(locatie)) {
                        throw new IOException(
                                "Destinația conține legături nepermise."
                        );
                    }

                    if (Files.isRegularFile(
                            locatie,
                            LinkOption.NOFOLLOW_LINKS
                    )) {
                        gasite.add(
                                radacinaNoua.relativize(locatie)
                                        .toString()
                                        .replace('\\', '/')
                        );
                    } else if (!Files.isDirectory(
                            locatie,
                            LinkOption.NOFOLLOW_LINKS
                    )) {
                        throw new IOException(
                                "Destinația conține o locație nepermisă."
                        );
                    }
                }
            }
        }

        if (!asteptate.equals(gasite)) {
            Set<String> lipsa = new HashSet<>(asteptate);
            lipsa.removeAll(gasite);

            Set<String> faraReferinta = new HashSet<>(gasite);
            faraReferinta.removeAll(asteptate);

            throw new IllegalStateException(
                    "Stocarea necesită reconciliere: "
                            + lipsa.size() + " fișiere lipsă, "
                            + faraReferinta.size() + " fără referință."
            );
        }
    }

    private void verificaStocat(
            String cheie,
            long dimensiune,
            String hashAsteptat) throws IOException {

        MessageDigest digest;

        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException eroare) {
            throw new IllegalStateException(eroare);
        }

        long total = 0;

        try (var intrare = stocare.deschide(cheie)) {
            byte[] buffer = new byte[8192];
            int cititi;

            while ((cititi = intrare.read(buffer)) != -1) {
                if (cititi > dimensiune - total) {
                    throw new IOException(
                            "Dimensiunea fișierului stocat nu corespunde."
                    );
                }

                digest.update(buffer, 0, cititi);
                total += cititi;
            }
        }

        if (total != dimensiune
                || !HexFormat.of().formatHex(digest.digest())
                .equals(hashAsteptat)) {

            throw new IOException(
                    "Integritatea fișierului stocat nu este confirmată."
            );
        }
    }

    private void inregistreazaCuratareLaRollback(List<String> cheiCreate) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_ROLLED_BACK) {
                            return;
                        }

                        for (int index = cheiCreate.size() - 1;
                             index >= 0;
                             index--) {

                            String cheie = cheiCreate.get(index);

                            try {
                                stocare.sterge(cheie);
                            } catch (IOException | RuntimeException eroare) {
                                log.error(
                                        "Curățarea fișierului {} a eșuat.",
                                        cheie,
                                        eroare
                                );
                            }
                        }
                    }
                }
        );
    }

    private static Path caleAbsoluta(String cale) {
        if (cale == null || cale.isBlank()) {
            throw new IllegalArgumentException(
                    "O cale necesară migrării nu este configurată."
            );
        }

        Path rezultat = Path.of(cale);

        if (!rezultat.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Migrarea necesită căi absolute pentru sursă și destinație."
            );
        }

        return rezultat.normalize();
    }

    private void verificaLocatie(Path locatie) throws IOException {
        if (!locatie.toRealPath().equals(locatie)) {
            throw new IOException(
                    "Sursa sau destinația conține o redirecționare nepermisă."
            );
        }
    }
}