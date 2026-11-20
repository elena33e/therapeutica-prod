package com.therapeutica.therapeutica_app.analize_medicale;

import com.therapeutica.therapeutica_app.analize_medicale.stocare.StocareDocumente;
import com.therapeutica.therapeutica_app.pacienti.Pacienti;
import com.therapeutica.therapeutica_app.pacienti.PacientiRepository;
import com.therapeutica.therapeutica_app.util.NotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.therapeutica.therapeutica_app.notificari.Notificare;
import com.therapeutica.therapeutica_app.notificari.NotificareRepository;

@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentMedicalService {

    private final PacientiRepository pacientiRepository;
    private final DocumentMedicalRepository documentMedicalRepository;
    private final FisierDocumentMedicalRepository fisierRepository;
    private final ValidareFisiereDocumenteService validareFisiere;
    private final LimiteDocumente limite;
    private final StocareDocumente stocare;
    private final NotificareRepository notificareRepository;

    @Transactional(rollbackFor = Exception.class)
    public DocumentMedical incarcaDocumente(
            MultipartFile[] files,
            UUID userId) throws IOException {

        Pacienti pacient = pacientiRepository.findByUserId(userId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "Profilul pacientului nu există."
                        )
                );

        List<MultipartFile> fisiere = validareFisiere.selecteaza(files);

        UUID documentId = UUID.randomUUID();

        DocumentMedical document = new DocumentMedical();
        document.setId(documentId);
        document.setPacient(pacient);
        document.setStatus(DocumentMedical.StatusDocument.INCARCAT);
        document.setCaleFisierStocare(documentId.toString());

        List<String> cheiCreate = new ArrayList<>();
        inregistreazaCuratareLaRollback(cheiCreate);

        List<FisierDocumentMedical> evidenta = new ArrayList<>();
        long totalOcteti = 0;
        String numeDocument = "";

        for (int index = 0; index < fisiere.size(); index++) {
            var validat = validareFisiere.valideaza(
                    fisiere.get(index),
                    limite.maxOctetiDocument() - totalOcteti
            );

            UUID fisierId = UUID.randomUUID();

            StocareDocumente.FisierStocat stocat;

            try (var sursa = new ByteArrayInputStream(validat.continut())) {
                stocat = stocare.salveaza(
                        documentId,
                        fisierId,
                        sursa,
                        limite.maxOctetiFisier()
                );
            }

            cheiCreate.add(stocat.cheieStocare());
            totalOcteti += stocat.dimensiuneOcteti();

            if (index == 0) {
                numeDocument = validat.numeAfisat();
            }

            FisierDocumentMedical fisier = new FisierDocumentMedical();
            fisier.setId(fisierId);
            fisier.setDocument(document);
            fisier.setCheieStocare(stocat.cheieStocare());
            fisier.setNumeOriginal(validat.numeOriginal());
            fisier.setNumeAfisat(validat.numeAfisat());
            fisier.setTipMedia(validat.tipMedia());
            fisier.setDimensiuneOcteti(stocat.dimensiuneOcteti());
            fisier.setOrdine(index + 1);
            fisier.setSha256(stocat.sha256());

            evidenta.add(fisier);
        }

        if (fisiere.size() > 1) {
            numeDocument += " (+ " + (fisiere.size() - 1) + " fișiere)";
        }

        document.setNumeFisier(numeDocument);

        documentMedicalRepository.saveAndFlush(document);
        fisierRepository.saveAllAndFlush(evidenta);

        notificaMedicul(document);

        return document;
    }

    @Transactional(readOnly = true)
    public List<DocumentMedical> getDocumentePacient(UUID userId) {
        Pacienti pacient = pacientiRepository.findByUserId(userId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "Profilul pacientului nu există."
                        )
                );

        return getDocumenteDupaPacientId(pacient.getId());
    }

    @Transactional(readOnly = true)
    public List<DocumentMedical> getDocumenteDupaPacientId(UUID pacientId) {
        return documentMedicalRepository
                .findByPacient_IdAndStatusNotOrderByDataIncarcareDesc(
                        pacientId,
                        DocumentMedical.StatusDocument.STERS_DE_PACIENT
                );
    }

    @Transactional
    public void retrageDocument(UUID documentId) {
        DocumentMedical document = documentMedicalRepository.findById(documentId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "Documentul nu a fost găsit."
                        )
                );

        document.setStatus(DocumentMedical.StatusDocument.STERS_DE_PACIENT);
        documentMedicalRepository.saveAndFlush(document);
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

    private void notificaMedicul(DocumentMedical document) {
        Pacienti pacient = document.getPacient();
        var medic = pacient.getMedic();

        if (medic == null || medic.getUser() == null) {
            return;
        }

        var destinatar = medic.getUser();

        if (!"MEDIC".equals(destinatar.getRol().name())) {
            return;
        }

        String numePacient = (
                pacient.getUser().getNume()
                        + " "
                        + pacient.getUser().getPrenume()
        ).strip();

        // Păstrăm mesajul în limita coloanei, inclusiv pentru nume lungi.
        int caractere = Math.min(
                200,
                numePacient.codePointCount(0, numePacient.length())
        );

        numePacient = numePacient.substring(
                0,
                numePacient.offsetByCodePoints(0, caractere)
        );

        Notificare notificare = Notificare.builder()
                .utilizatorDestinatar(destinatar)
                .titlu("Document medical încărcat")
                .mesaj(
                        "Pacientul " + numePacient
                                + " a încărcat un document medical."
                )
                .linkActiune(
                        "/documente/" + document.getId() + "/sursa"
                )
                .citita(false)
                .build();

        notificareRepository.saveAndFlush(notificare);
    }
}