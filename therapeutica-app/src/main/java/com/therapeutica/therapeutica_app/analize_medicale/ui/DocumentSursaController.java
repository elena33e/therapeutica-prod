package com.therapeutica.therapeutica_app.analize_medicale.ui;

import com.therapeutica.therapeutica_app.analize_medicale.DocumentMedical;
import com.therapeutica.therapeutica_app.analize_medicale.DocumentMedicalRepository;
import com.therapeutica.therapeutica_app.analize_medicale.FisierDocumentMedical;
import com.therapeutica.therapeutica_app.analize_medicale.FisiereDocumenteService;
import com.therapeutica.therapeutica_app.audit.AuditLog;
import com.therapeutica.therapeutica_app.audit.AuditService;
import com.therapeutica.therapeutica_app.security.AccesPacientService;
import com.therapeutica.therapeutica_app.util.AccesInterzisException;
import com.therapeutica.therapeutica_app.util.NotFoundException;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Controller
@RequestMapping("/documente")
@RequiredArgsConstructor
public class DocumentSursaController {

    private static final Set<String> TIPURI_PREVIZUALIZABILE = Set.of(
            "application/pdf",
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp"
    );

    private final DocumentMedicalRepository documentMedicalRepository;
    private final FisiereDocumenteService fisiereDocumenteService;
    private final AccesPacientService accesPacientService;
    private final AuditService auditService;

    @GetMapping("/{documentId}/sursa")
    public String afiseazaSursa(
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "0") int index,
            HttpSession session,
            Authentication authentication,
            Model model) {

        DocumentMedical document =
                incarcaDocumentAutorizat(documentId, session, authentication);

        List<FisierDocumentMedical> fisiere =
                fisiereDocumenteService.listeaza(documentId);

        verificaIndex(index, fisiere.size());

        FisierDocumentMedical fisierSelectat = fisiere.get(index);
        fisiereDocumenteService.verificaDisponibilitate(fisierSelectat);

        model.addAttribute("document", document);
        model.addAttribute(
                "numeFisiere",
                fisiere.stream()
                        .map(FisierDocumentMedical::getNumeAfisat)
                        .toList()
        );
        model.addAttribute("indexSelectat", index);
        model.addAttribute(
                "previzualizabil",
                TIPURI_PREVIZUALIZABILE.contains(
                        fisierSelectat.getTipMedia()
                )
        );

        return "documente/sursa";
    }

    @GetMapping("/{documentId}/fisier")
    public ResponseEntity<Resource> deschideFisier(
            @PathVariable UUID documentId,
            @RequestParam(defaultValue = "0") int index,
            @RequestParam(defaultValue = "false") boolean descarcare,
            HttpSession session,
            Authentication authentication) {

        DocumentMedical document =
                incarcaDocumentAutorizat(documentId, session, authentication);

        List<FisierDocumentMedical> fisiere =
                fisiereDocumenteService.listeaza(documentId);

        verificaIndex(index, fisiere.size());

        FisierDocumentMedical fisier = fisiere.get(index);
        byte[] continut = fisiereDocumenteService.citeste(fisier);

        String dispozitie = !descarcare
                && TIPURI_PREVIZUALIZABILE.contains(fisier.getTipMedia())
                ? "inline"
                : "attachment";

        ContentDisposition contentDisposition = ContentDisposition
                .builder(dispozitie)
                .filename(
                        fisier.getNumeAfisat(),
                        StandardCharsets.UTF_8
                )
                .build();

        auditService.inregistreaza(
                auditService.utilizatorCurent(session),
                AuditLog.ActiuneAudit.VIZUALIZARE,
                AuditLog.TipResursa.DOCUMENT_MEDICAL,
                documentId,
                document.getPacient().getId()
        );

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(fisier.getTipMedia()))
                .contentLength(continut.length)
                .cacheControl(CacheControl.noStore())
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        contentDisposition.toString()
                )
                .body(new ByteArrayResource(continut));
    }

    private DocumentMedical incarcaDocumentAutorizat(
            UUID documentId,
            HttpSession session,
            Authentication authentication) {

        if (authentication == null) {
            throw new AccesInterzisException(
                    "Autentificare necesară."
            );
        }

        DocumentMedical document = documentMedicalRepository
                .findByIdWithPacientSiUser(documentId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "Documentul nu a fost găsit."
                        )
                );

        boolean esteMedic = authentication.getAuthorities().stream()
                .anyMatch(authority ->
                        "ROLE_MEDIC".equals(authority.getAuthority())
                );

        if (esteMedic) {
            accesPacientService.verificaAccesMedic(
                    session,
                    document.getPacient().getId()
            );
        } else {
            accesPacientService.verificaAccesPropriu(
                    session,
                    document.getPacient().getId()
            );
        }

        if (document.getStatus()
                == DocumentMedical.StatusDocument.STERS_DE_PACIENT) {

            throw new NotFoundException(
                    "Documentul a fost retras din dosar."
            );
        }

        return document;
    }

    private void verificaIndex(int index, int numarFisiere) {
        if (index < 0 || index >= numarFisiere) {
            throw new NotFoundException(
                    "Fișierul solicitat nu există."
            );
        }
    }
}