package com.therapeutica.therapeutica_app.analize_medicale.stocare;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.nio.file.DirectoryNotEmptyException;

@Component
public class StocareLocalaDocumente implements StocareDocumente {

    private static final Pattern CHEIE_PERMISA = Pattern.compile(
            "^[A-Za-z0-9_-]([A-Za-z0-9_.-]*[A-Za-z0-9_-])?"
                    + "(/[A-Za-z0-9_-]([A-Za-z0-9_.-]*[A-Za-z0-9_-])?)*$"
    );

    private final Path radacina;

    public StocareLocalaDocumente(
            @Value("${app.storage.documents-dir}") String director) {

        if (director == null || director.isBlank()) {
            throw new IllegalArgumentException(
                    "Directorul documentelor trebuie configurat."
            );
        }

        Path locatie = Path.of(director);

        if (!locatie.isAbsolute()) {
            throw new IllegalArgumentException(
                    "Directorul documentelor trebuie să fie o cale absolută."
            );
        }

        this.radacina = locatie.normalize();

        if (this.radacina.getParent() == null) {
            throw new IllegalArgumentException(
                    "Documentele necesită un director dedicat."
            );
        }
    }

    @Override
    public FisierStocat salveaza(
            UUID documentId,
            UUID fisierId,
            InputStream sursa,
            long limitaOcteti) throws IOException {

        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(fisierId, "fisierId");
        Objects.requireNonNull(sursa, "sursa");

        if (limitaOcteti <= 0) {
            throw new IllegalArgumentException(
                    "Limita fișierului trebuie să fie pozitivă."
            );
        }

        String cheie = documentId + "/" + fisierId;
        Path destinatie = rezolva(cheie);

        verificaDirectoare(destinatie.getParent(), true);

        MessageDigest digest = sha256();
        long total = 0;
        boolean creat = false;

        try (OutputStream iesire = Files.newOutputStream(
                destinatie,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS
        )) {
            creat = true;

            byte[] buffer = new byte[8192];
            int cititi;

            while ((cititi = sursa.read(buffer)) != -1) {
                if (cititi == 0) {
                    continue;
                }

                if (cititi > limitaOcteti - total) {
                    throw new IllegalArgumentException(
                            "Fișierul depășește dimensiunea maximă permisă."
                    );
                }

                iesire.write(buffer, 0, cititi);
                digest.update(buffer, 0, cititi);
                total += cititi;
            }

            if (total == 0) {
                throw new IllegalArgumentException(
                        "Fișierul este gol."
                );
            }
        } catch (IOException | RuntimeException eroare) {
            if (creat) {
                try {
                    sterge(cheie);
                } catch (IOException eroareCuratare) {
                    eroare.addSuppressed(eroareCuratare);
                }
            }

            throw eroare;
        }

        return new FisierStocat(
                cheie,
                total,
                HexFormat.of().formatHex(digest.digest())
        );
    }

    @Override
    public InputStream deschide(String cheieStocare) throws IOException {
        Path fisier = rezolva(cheieStocare);

        verificaDirectoare(fisier.getParent(), false);
        verificaFisier(fisier);

        return Files.newInputStream(
                fisier,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS
        );
    }

    @Override
    public boolean sterge(String cheieStocare) throws IOException {
        Path fisier = rezolva(cheieStocare);

        try {
            verificaDirectoare(fisier.getParent(), false);
            verificaFisier(fisier);

            boolean sters = Files.deleteIfExists(fisier);

            if (sters) {
                stergeDirectorGol(fisier.getParent());
            }

            return sters;
        } catch (NoSuchFileException lipsa) {
            return false;
        }
    }

    private void stergeDirectorGol(Path director) throws IOException {
        if (director.equals(radacina)) {
            return;
        }

        try {
            Files.delete(director);
        } catch (DirectoryNotEmptyException | NoSuchFileException ignorata) {
            // Directorul este păstrat dacă mai conține alte fișiere.
        }
    }

    private Path rezolva(String cheie) {
        if (cheie == null
                || cheie.length() > 512
                || !CHEIE_PERMISA.matcher(cheie).matches()) {

            throw new IllegalArgumentException(
                    "Cheia de stocare nu este validă."
            );
        }

        Path rezultat = radacina.resolve(cheie).normalize();

        if (!rezultat.startsWith(radacina)
                || rezultat.equals(radacina)) {

            throw new IllegalArgumentException(
                    "Cheia de stocare nu este validă."
            );
        }

        return rezultat;
    }

    private void verificaDirectoare(
            Path director,
            boolean creeaza) throws IOException {

        // Verificăm și componentele dinaintea rădăcinii configurate.
        Path curent = director.getRoot();

        for (Path componenta : director) {
            curent = curent.resolve(componenta);

            BasicFileAttributes atribute;

            try {
                atribute = atribute(curent);
            } catch (NoSuchFileException lipsa) {
                if (!creeaza) {
                    throw lipsa;
                }

                try {
                    Files.createDirectory(curent);
                } catch (FileAlreadyExistsException existent) {
                    // O încărcare concurentă poate crea același director.
                    // Îl verificăm înainte de a continua.
                }

                atribute = atribute(curent);
            }

            if (atribute.isSymbolicLink()
                    || !atribute.isDirectory()) {

                throw new IOException(
                        "Stocarea conține o componentă nepermisă."
                );
            }

            // Detectează și redirecționările rezolvate de sistemul de fișiere.
            if (!curent.toRealPath().equals(curent)) {
                throw new IOException(
                        "Stocarea conține o redirecționare nepermisă."
                );
            }
        }
    }

    private void verificaFisier(Path fisier) throws IOException {
        BasicFileAttributes atribute = atribute(fisier);

        if (atribute.isSymbolicLink()
                || !atribute.isRegularFile()) {

            throw new IOException(
                    "Locația solicitată nu este un fișier permis."
            );
        }
    }

    private BasicFileAttributes atribute(Path locatie) throws IOException {
        return Files.readAttributes(
                locatie,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
    }

    private MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException eroare) {
            throw new IllegalStateException(
                    "SHA-256 nu este disponibil.",
                    eroare
            );
        }
    }
}