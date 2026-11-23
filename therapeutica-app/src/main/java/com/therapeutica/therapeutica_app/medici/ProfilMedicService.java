package com.therapeutica.therapeutica_app.medici;

import com.therapeutica.therapeutica_app.security.AccesContService;
import com.therapeutica.therapeutica_app.util.NotFoundException;
import com.therapeutica.therapeutica_app.utilizatori.RoleType;
import com.therapeutica.therapeutica_app.utilizatori.UtilizatoriRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.Locale;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
public class ProfilMedicService {

    private final AccesContService accesContService;
    private final UtilizatoriRepository utilizatoriRepository;
    private final MediciRepository mediciRepository;

    public record ProfilSalvat(
            UUID userId,
            String nume,
            String prenume,
            String email
    ) {}

    @Transactional
    public ProfilSalvat salveaza(
            UUID userId,
            @Valid ProfilMedicForm formular) {

        var utilizator = accesContService.verificaAccesProfil(
                userId,
                RoleType.MEDIC
        );

        Medici medic = mediciRepository.findByUserId(userId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "Profilul medicului nu a fost găsit."
                        )
                );

        String email = text(formular.getEmail())
                .toLowerCase(Locale.ROOT);

        if (utilizatoriRepository.existsByEmailIgnoreCaseAndIdNot(
                email,
                userId
        )) {
            throw new EmailDejaFolositException();
        }

        utilizator.setNume(text(formular.getNume()));
        utilizator.setPrenume(text(formular.getPrenume()));
        utilizator.setEmail(email);

        medic.setSpecializare(text(formular.getSpecializare()));

        String telefon = text(formular.getTelefon());
        medic.setTelefon(telefon.isEmpty() ? null : telefon);

        try {
            utilizatoriRepository.save(utilizator);
            mediciRepository.save(medic);

            // Detectăm aici inclusiv un email ocupat între timp.
            utilizatoriRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (esteConflictEmail(e)) {
                throw new EmailDejaFolositException();
            }

            throw e;
        }

        return new ProfilSalvat(
                utilizator.getId(),
                utilizator.getNume(),
                utilizator.getPrenume(),
                utilizator.getEmail()
        );
    }

    private boolean esteConflictEmail(Throwable eroare) {
        Throwable cauza = eroare;

        while (cauza != null) {
            if (cauza instanceof ConstraintViolationException incalcare
                    && "utilizatori_email_key".equals(
                    incalcare.getConstraintName()
            )) {
                return true;
            }

            cauza = cauza.getCause();
        }

        return false;
    }

    private static String text(String valoare) {
        return valoare == null ? "" : valoare.trim();
    }

    public static class EmailDejaFolositException
            extends RuntimeException {

        public EmailDejaFolositException() {
            super("Acest email este deja utilizat de alt cont.");
        }
    }
}