package com.therapeutica.therapeutica_app.cod_inregistrare;

import com.therapeutica.therapeutica_app.cod_inregistrare.dto.CodInregistrareDTO;
import com.therapeutica.therapeutica_app.cod_inregistrare.dto.GenerareCodRequest;
import com.therapeutica.therapeutica_app.cod_inregistrare.dto.GenerareCodResponse;
import com.therapeutica.therapeutica_app.medici.MediciRepository;
import com.therapeutica.therapeutica_app.notificari.external.WhatsAppService;
import com.therapeutica.therapeutica_app.pacienti.PacientiRepository;
import com.therapeutica.therapeutica_app.security.AccesContService;
import com.therapeutica.therapeutica_app.util.AccesInterzisException;
import com.therapeutica.therapeutica_app.util.NotFoundException;
import com.therapeutica.therapeutica_app.utilizatori.RoleType;
import com.therapeutica.therapeutica_app.utilizatori.UtilizatoriRepository;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CodInregistrareService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CARACTERE =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    private final CodInregistrareRepository codInregistrareRepository;
    private final UtilizatoriRepository utilizatoriRepository;
    private final PacientiRepository pacientiRepository;
    private final MediciRepository mediciRepository;
    private final WhatsAppService whatsappService;
    private final AccesContService accesContService;
    private final Validator validator;

    @Transactional(readOnly = true)
    public RoleType getRolDestinatarPermis() {
        RoleType rol = PoliticaInvitatii.destinatarPermis(
                accesContService.utilizatorCurent().getRol()
        );

        if (rol == null) {
            throw new AccesInterzisException(
                    "Nu poți genera invitații."
            );
        }

        return rol;
    }

    @Transactional
    public GenerareCodResponse generareCodInregistrare(
            GenerareCodRequest request) {

        var autor = accesContService.utilizatorCurent();
        RoleType rolPermis =
                PoliticaInvitatii.destinatarPermis(autor.getRol());

        if (rolPermis == null
                || (request.getRolDestinatar() != null
                && request.getRolDestinatar() != rolPermis)) {

            throw new AccesInterzisException(
                    "Nu poți invita un utilizator cu acest rol."
            );
        }

        // Autorul și rolul sunt stabilite pe server.
        request.setMedicId(autor.getId());
        request.setRolDestinatar(rolPermis);

        request.setEmailDestinatar(text(request.getEmailDestinatar()));
        request.setNumeDestinatar(text(request.getNumeDestinatar()));
        request.setPrenumeDestinatar(text(request.getPrenumeDestinatar()));
        request.setCnpDestinatar(text(request.getCnpDestinatar()));

        var erori = validator.validate(request);

        if (!erori.isEmpty()) {
            String mesaj = erori.stream()
                    .map(eroare -> eroare.getMessage())
                    .sorted()
                    .findFirst()
                    .orElse("Date invalide.");

            return eroare(mesaj);
        }

        if (!request.getCnpDestinatar().matches("[0-9]{13}")) {
            return eroare("CNP-ul trebuie să conțină exact 13 cifre.");
        }

        if (rolPermis == RoleType.PACIENT
                && !mediciRepository.existsById(autor.getId())) {

            return eroare(
                    "Profilul medicului trebuie completat înainte de invitarea pacienților."
            );
        }

        if (utilizatoriRepository.existsByEmailIgnoreCase(
                request.getEmailDestinatar())) {

            return eroare("Există deja un cont cu acest email.");
        }

        if (codInregistrareRepository
                .existsByEmailDestinatarIgnoreCaseAndStatus(
                        request.getEmailDestinatar(),
                        CodInregistrare.StatusCod.NEUTILIZAT
                )) {

            return eroare(
                    "Există deja o invitație activă pentru acest email."
            );
        }

        if (pacientiRepository.existsByCnp(request.getCnpDestinatar())
                || codInregistrareRepository
                .existsByCnpDestinatarAndStatus(
                        request.getCnpDestinatar(),
                        CodInregistrare.StatusCod.NEUTILIZAT
                )) {

            return eroare(
                    "Există deja un pacient sau o invitație activă cu acest CNP."
            );
        }

        CodInregistrare cod = new CodInregistrare();
        cod.setCodUnic(genereazaCodUnic());
        cod.setStatus(CodInregistrare.StatusCod.NEUTILIZAT);
        cod.setGeneratDe(autor);
        cod.setRolDestinatar(rolPermis);
        cod.setEmailDestinatar(request.getEmailDestinatar());
        cod.setNumeDestinatar(request.getNumeDestinatar());
        cod.setPrenumeDestinatar(request.getPrenumeDestinatar());
        cod.setCnpDestinatar(request.getCnpDestinatar());
        cod.setTelefonDestinatar(text(request.getTelefonDestinatar()));

        codInregistrareRepository.save(cod);

        GenerareCodResponse rezultat = GenerareCodResponse.builder()
                .codUnic(cod.getCodUnic())
                .emailDestinatar(cod.getEmailDestinatar())
                .status(cod.getStatus())
                .mesaj("Cod generat cu succes.")
                .build();

        if (!text(cod.getTelefonDestinatar()).isEmpty()) {
            try {
                rezultat.setWhatsappLink(
                        whatsappService.genereazaLinkMesaj(
                                cod.getTelefonDestinatar(),
                                cod.getCodUnic()
                        )
                );
            } catch (IllegalArgumentException ignored) {
                // Codul poate fi transmis și fără link WhatsApp.
            }
        }

        return rezultat;
    }

    @Transactional(readOnly = true)
    public List<CodInregistrareDTO> getCoduriGestionabile() {
        var autor = accesContService.utilizatorCurent();

        List<CodInregistrare> coduri;

        if (autor.getRol() == RoleType.ADMIN) {
            coduri = codInregistrareRepository
                    .findByRolDestinatarOrderByCreatedAtDesc(RoleType.MEDIC);
        } else if (autor.getRol() == RoleType.MEDIC) {
            coduri = codInregistrareRepository
                    .findByGeneratDeId(autor.getId())
                    .stream()
                    .filter(cod ->
                            cod.getRolDestinatar() == RoleType.PACIENT
                    )
                    .toList();
        } else {
            throw new AccesInterzisException(
                    "Nu poți consulta invitațiile."
            );
        }

        return coduri.stream().map(this::mapToDTO).toList();
    }

    @Transactional
    public void anuleazaInvitatia(UUID id) {
        var autor = accesContService.utilizatorCurent();

        CodInregistrare cod = codInregistrareRepository
                .findByIdForUpdate(id)
                .orElseThrow(() ->
                        new NotFoundException("Invitația nu a fost găsită.")
                );

        boolean permis =
                (autor.getRol() == RoleType.ADMIN
                        && cod.getRolDestinatar() == RoleType.MEDIC)
                        || (autor.getRol() == RoleType.MEDIC
                        && cod.getRolDestinatar() == RoleType.PACIENT
                        && autor.getId().equals(
                        cod.getGeneratDe().getId()
                ));

        if (!permis) {
            throw new AccesInterzisException(
                    "Nu poți anula această invitație."
            );
        }

        if (cod.getStatus() != CodInregistrare.StatusCod.NEUTILIZAT
                || cod.getAtribuit() != null) {

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "O invitație utilizată nu mai poate fi anulată."
            );
        }

        codInregistrareRepository.delete(cod);
    }

    private String genereazaCodUnic() {
        String cod;

        do {
            StringBuilder rezultat = new StringBuilder(8);
            for (int i = 0; i < 8; i++) {
                rezultat.append(CARACTERE.charAt(
                        RANDOM.nextInt(CARACTERE.length())
                ));
            }
            cod = rezultat.toString();
        } while (codInregistrareRepository.existsByCodUnic(cod));

        return cod;
    }

    private CodInregistrareDTO mapToDTO(CodInregistrare cod) {
        CodInregistrareDTO dto = new CodInregistrareDTO();
        dto.setId(cod.getId());
        dto.setCodUnic(cod.getCodUnic());
        dto.setStatus(cod.getStatus());
        dto.setCreatedAt(cod.getCreatedAt());
        dto.setGeneratDe(cod.getGeneratDe().getId());
        dto.setAtribuit(
                cod.getAtribuit() == null
                        ? null
                        : cod.getAtribuit().getId()
        );
        dto.setNumeDestinatar(cod.getNumeDestinatar());
        dto.setPrenumeDestinatar(cod.getPrenumeDestinatar());
        dto.setEmailDestinatar(cod.getEmailDestinatar());
        dto.setCnpDestinatar(cod.getCnpDestinatar());
        dto.setTelefonDestinatar(cod.getTelefonDestinatar());
        dto.setRolDestinatar(cod.getRolDestinatar());
        return dto;
    }

    private static GenerareCodResponse eroare(String mesaj) {
        return new GenerareCodResponse("Eroare: " + mesaj);
    }

    private static String text(String valoare) {
        return valoare == null ? "" : valoare.trim();
    }
}