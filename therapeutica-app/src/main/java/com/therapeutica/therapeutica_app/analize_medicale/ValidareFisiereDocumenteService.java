package com.therapeutica.therapeutica_app.analize_medicale;

import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.Loader;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.io.InputStream;

@Service
@RequiredArgsConstructor
public class ValidareFisiereDocumenteService {

    private static final Map<String, String> TIPURI_PERMISE = Map.of(
            "pdf", "application/pdf",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "webp", "image/webp"
    );

    private final LimiteDocumente limite;

    public record FisierValidat(
            String numeOriginal,
            String numeAfisat,
            String tipMedia,
            byte[] continut
    ) {
    }

    public List<MultipartFile> selecteaza(MultipartFile[] files) {
        if (files == null) {
            throw new IllegalArgumentException(
                    "Selectează cel puțin un fișier."
            );
        }

        List<MultipartFile> fisiere = Arrays.stream(files)
                .filter(file -> file != null)
                .filter(file -> !esteCampNecompletat(file))
                .toList();

        if (fisiere.isEmpty()) {
            throw new IllegalArgumentException(
                    "Selectează cel puțin un fișier."
            );
        }

        if (fisiere.size() > limite.maxFisiereDocument()) {
            throw new IllegalArgumentException(
                    "Sunt permise cel mult "
                            + limite.maxFisiereDocument()
                            + " fișiere într-un document."
            );
        }

        long totalDeclarat = 0;

        for (MultipartFile file : fisiere) {
            String nume = numeOriginal(file);
            tipDinExtensie(nume);

            long dimensiune = file.getSize();

            if (dimensiune < 0) {
                throw new IllegalArgumentException(
                        "Dimensiunea unui fișier nu este validă."
                );
            }

            if (dimensiune > limite.maxOctetiFisier()) {
                throw limitaFisier();
            }

            if (dimensiune
                    > limite.maxOctetiDocument() - totalDeclarat) {
                throw limitaDocument();
            }

            totalDeclarat += dimensiune;
        }

        return fisiere;
    }

    public FisierValidat valideaza(
            MultipartFile file,
            long octetiRamasi) throws IOException {

        try (var intrare = file.getInputStream()) {
            return valideaza(
                    file.getOriginalFilename(),
                    intrare,
                    octetiRamasi
            );
        }
    }

    // Apelantul este responsabil pentru închiderea fluxului.
    public FisierValidat valideaza(
            String numeFisier,
            InputStream intrare,
            long octetiRamasi) throws IOException {

        String nume = numeOriginal(numeFisier);
        String tipAsteptat = tipDinExtensie(nume);

        if (octetiRamasi <= 0) {
            throw limitaDocument();
        }

        long limitaCitire = Math.min(
                limite.maxOctetiFisier(),
                octetiRamasi
        );

        byte[] continut = intrare.readNBytes((int) limitaCitire + 1);

        if (continut.length > limitaCitire) {
            if (limitaCitire < limite.maxOctetiFisier()) {
                throw limitaDocument();
            }

            throw limitaFisier();
        }

        if (continut.length == 0) {
            throw new IllegalArgumentException(
                    "Fișierele goale nu sunt acceptate."
            );
        }

        String tipDetectat;

        try {
            tipDetectat = detecteazaSiVerifica(continut);
        } catch (IOException | RuntimeException eroare) {
            throw new IllegalArgumentException(
                    "Fișierul nu este un PDF sau o imagine validă "
                            + "în formatele acceptate.",
                    eroare
            );
        }

        if (!tipAsteptat.equals(tipDetectat)) {
            throw new IllegalArgumentException(
                    "Extensia fișierului nu corespunde conținutului său."
            );
        }

        return new FisierValidat(
                nume,
                numeAfisat(nume),
                tipDetectat,
                continut
        );
    }

    private String detecteazaSiVerifica(byte[] continut)
            throws IOException {

        String prefix = new String(
                continut,
                0,
                Math.min(5, continut.length),
                StandardCharsets.US_ASCII
        );

        if ("%PDF-".equals(prefix)) {
            verificaPdf(continut);
            return "application/pdf";
        }

        return verificaImagine(continut);
    }

    private void verificaPdf(byte[] continut) throws IOException {
        int sfarsit = continut.length;

        while (sfarsit > 0
                && esteSpatiuPdf(continut[sfarsit - 1] & 0xff)) {
            sfarsit--;
        }

        if (sfarsit < 5
                || !"%%EOF".equals(new String(
                continut,
                sfarsit - 5,
                5,
                StandardCharsets.US_ASCII
        ))) {

            throw new IOException(
                    "Documentul PDF nu are marcajul final."
            );
        }

        try (var pdf = Loader.loadPDF(continut)) {
            if (pdf.isEncrypted() || pdf.getNumberOfPages() <= 0) {
                throw new IOException(
                        "Documentul PDF nu poate fi acceptat."
                );
            }
        }
    }

    private String verificaImagine(byte[] continut) throws IOException {
        try (var intrare = new MemoryCacheImageInputStream(
                new ByteArrayInputStream(continut)
        )) {
            var cititoare = ImageIO.getImageReaders(intrare);

            if (!cititoare.hasNext()) {
                throw new IOException(
                        "Formatul imaginii nu este recunoscut."
                );
            }

            ImageReader cititor = cititoare.next();

            try {
                String tip = switch (
                        cititor.getFormatName().toLowerCase(Locale.ROOT)
                        ) {
                    case "jpg", "jpeg" -> "image/jpeg";
                    case "png" -> "image/png";
                    case "gif" -> "image/gif";
                    case "webp" -> "image/webp";
                    default -> throw new IOException(
                            "Formatul imaginii nu este permis."
                    );
                };

                intrare.seek(0);
                cititor.setInput(intrare, false, true);

                int latime = cititor.getWidth(0);
                int inaltime = cititor.getHeight(0);

                if (latime <= 0
                        || inaltime <= 0
                        || (long) latime * inaltime
                        > limite.maxPixeliImagine()) {

                    throw new IOException(
                            "Dimensiunile imaginii nu sunt permise."
                    );
                }

                BufferedImage imagine = cititor.read(0);

                if (imagine == null) {
                    throw new IOException(
                            "Imaginea nu poate fi decodată."
                    );
                }

                imagine.flush();
                return tip;
            } finally {
                cititor.dispose();
            }
        }
    }

    private String numeOriginal(MultipartFile file) {
        return numeOriginal(file.getOriginalFilename());
    }

    private String numeOriginal(String original) {
        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException(
                    "Un fișier are numele lipsă."
            );
        }

        String nume = original.replace('\\', '/');
        nume = nume.substring(nume.lastIndexOf('/') + 1).strip();
        nume = Normalizer.normalize(nume, Normalizer.Form.NFC);

        if (nume.isBlank()
                || nume.length() > 255
                || nume.chars().anyMatch(Character::isISOControl)
                || nume.lastIndexOf('.') <= 0) {

            throw new IllegalArgumentException(
                    "Numele unui fișier nu este valid."
            );
        }

        return nume;
    }

    private String tipDinExtensie(String nume) {
        String extensie = nume.substring(nume.lastIndexOf('.') + 1)
                .toLowerCase(Locale.ROOT);

        String tip = TIPURI_PERMISE.get(extensie);

        if (tip == null) {
            throw new IllegalArgumentException(
                    "Formate acceptate: PDF, JPG, JPEG, PNG, GIF și WEBP."
            );
        }

        return tip;
    }

    private String numeAfisat(String nume) {
        int punct = nume.lastIndexOf('.');

        String baza = Normalizer.normalize(
                        nume.substring(0, punct),
                        Normalizer.Form.NFD
                )
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-zA-Z0-9_-]", "_");

        baza = baza.substring(0, Math.min(baza.length(), 120));

        return baza + nume.substring(punct).toLowerCase(Locale.ROOT);
    }

    private boolean esteCampNecompletat(MultipartFile file) {
        String nume = file.getOriginalFilename();

        return file.isEmpty()
                && (nume == null || nume.isBlank());
    }

    private boolean esteSpatiuPdf(int valoare) {
        return valoare == 0
                || valoare == 9
                || valoare == 10
                || valoare == 12
                || valoare == 13
                || valoare == 32;
    }

    private IllegalArgumentException limitaFisier() {
        return new IllegalArgumentException(
                "Un fișier depășește dimensiunea maximă permisă."
        );
    }

    private IllegalArgumentException limitaDocument() {
        return new IllegalArgumentException(
                "Documentul depășește dimensiunea totală permisă."
        );
    }
}