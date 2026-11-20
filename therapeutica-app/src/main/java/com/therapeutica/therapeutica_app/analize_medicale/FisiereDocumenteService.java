package com.therapeutica.therapeutica_app.analize_medicale;

import com.therapeutica.therapeutica_app.analize_medicale.stocare.StocareDocumente;
import com.therapeutica.therapeutica_app.util.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class FisiereDocumenteService {

    private final FisierDocumentMedicalRepository fisierRepository;
    private final StocareDocumente stocare;

    @Transactional(readOnly = true)
    public List<FisierDocumentMedical> listeaza(UUID documentId) {
        return fisierRepository
                .findByDocument_IdOrderByOrdineAsc(documentId);
    }

    public void verificaDisponibilitate(FisierDocumentMedical fisier) {
        try (var intrare = stocare.deschide(fisier.getCheieStocare())) {
            // Deschiderea verifică accesibilitatea fișierului.
            // Fluxul este închis automat la ieșirea din bloc.
        } catch (IOException | IllegalArgumentException eroare) {
            log.warn(
                    "Verificarea disponibilității fișierului {} a eșuat.",
                    fisier.getId(),
                    eroare
            );

            throw indisponibil();
        }
    }

    public byte[] citeste(FisierDocumentMedical fisier) {
        long dimensiune = fisier.getDimensiuneOcteti();

        if (dimensiune <= 0 || dimensiune > Integer.MAX_VALUE - 1L) {
            throw indisponibil();
        }

        byte[] continut;

        try (var intrare = stocare.deschide(fisier.getCheieStocare())) {
            continut = intrare.readNBytes((int) dimensiune + 1);
        } catch (IOException | IllegalArgumentException eroare) {
            log.warn(
                    "Citirea fișierului {} a eșuat.",
                    fisier.getId(),
                    eroare
            );
            throw indisponibil();
        }

        if (continut.length != dimensiune
                || !sha256(continut).equals(fisier.getSha256())) {

            log.error(
                    "Verificarea integrității fișierului {} a eșuat.",
                    fisier.getId()
            );

            throw indisponibil();
        }

        return continut;
    }

    private String sha256(byte[] continut) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(continut);

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException eroare) {
            throw new IllegalStateException(
                    "SHA-256 nu este disponibil.",
                    eroare
            );
        }
    }

    private NotFoundException indisponibil() {
        return new NotFoundException(
                "Fișierul documentului nu este disponibil."
        );
    }
}