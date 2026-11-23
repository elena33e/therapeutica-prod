package com.therapeutica.therapeutica_app.pacienti;

import com.therapeutica.therapeutica_app.security.AccesContService;
import com.therapeutica.therapeutica_app.util.CnpValidator;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

@Service
@Validated
@RequiredArgsConstructor
public class ProfilPacientService {

    private static final ZoneId ZONA =
            ZoneId.of("Europe/Bucharest");

    private final AccesContService accesContService;
    private final UtilizatoriRepository utilizatoriRepository;
    private final PacientiRepository pacientiRepository;

    public record ProfilSalvat(
            UUID userId,
            String nume,
            String prenume,
            String email
    ) {}

    @Transactional
    public ProfilSalvat salveaza(
            UUID userId,
            @Valid ProfilPacientForm formular) {

        var utilizator = accesContService.verificaAccesProfil(
                userId,
                RoleType.PACIENT
        );

        Pacienti pacient = pacientiRepository.findByUserId(userId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "Profilul pacientului nu a fost găsit."
                        )
                );

        String cnp = text(formular.getCnp());

        if (!CnpValidator.esteValid(cnp)) {
            throw new CampProfilInvalidException(
                    "cnp",
                    "CNP-ul introdus nu are o structură validă."
            );
        }

        if (pacientiRepository.existsByCnpAndIdNot(
                cnp,
                pacient.getId()
        )) {
            throw cnpDuplicat();
        }

        String email = text(formular.getEmail())
                .toLowerCase(Locale.ROOT);

        if (utilizatoriRepository.existsByEmailIgnoreCaseAndIdNot(
                email,
                userId
        )) {
            throw emailDuplicat();
        }

        LocalDate dataNasterii = formular.getDataNasterii();

        if (dataNasterii != null
                && dataNasterii.isAfter(LocalDate.now(ZONA))) {

            throw new CampProfilInvalidException(
                    "dataNasterii",
                    "Data nașterii nu poate fi în viitor."
            );
        }

        Double greutate = formular.getGreutate();

        if (greutate != null && !Double.isFinite(greutate)) {
            throw new CampProfilInvalidException(
                    "greutate",
                    "Introdu o greutate numerică validă."
            );
        }

        String grupaSangvina = text(formular.getGrupaSangvina());

        if (!grupaSangvina.isEmpty()
                && !ProfilPacientForm.GRUPE_SANGVINE.contains(
                grupaSangvina
        )) {

            throw new CampProfilInvalidException(
                    "grupaSangvina",
                    "Selectează o grupă sangvină din listă."
            );
        }

        utilizator.setNume(text(formular.getNume()));
        utilizator.setPrenume(text(formular.getPrenume()));
        utilizator.setEmail(email);

        pacient.setCnp(cnp);
        pacient.setDataNasterii(dataNasterii);
        pacient.setSex(formular.getSex());
        pacient.setInaltime(formular.getInaltime());
        pacient.setGreutate(greutate);
        pacient.setGrupaSangvina(
                grupaSangvina.isEmpty() ? null : grupaSangvina
        );

        try {
            utilizatoriRepository.save(utilizator);
            pacientiRepository.save(pacient);

            utilizatoriRepository.flush();
        } catch (DataIntegrityViolationException e) {
            String constrangere = numeConstrangere(e);

            if ("utilizatori_email_key".equals(constrangere)) {
                throw emailDuplicat();
            }

            if ("uq_pacienti_cnp".equals(constrangere)) {
                throw cnpDuplicat();
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

    private static CampProfilInvalidException emailDuplicat() {
        return new CampProfilInvalidException(
                "email",
                "Acest email este deja utilizat de alt cont."
        );
    }

    private static CampProfilInvalidException cnpDuplicat() {
        return new CampProfilInvalidException(
                "cnp",
                "Există deja un pacient cu acest CNP."
        );
    }

    private static String numeConstrangere(Throwable eroare) {
        Throwable cauza = eroare;

        while (cauza != null) {
            if (cauza instanceof ConstraintViolationException incalcare) {
                return incalcare.getConstraintName();
            }

            cauza = cauza.getCause();
        }

        return null;
    }

    private static String text(String valoare) {
        return valoare == null ? "" : valoare.trim();
    }

    public static class CampProfilInvalidException
            extends RuntimeException {

        private final String camp;

        public CampProfilInvalidException(
                String camp,
                String mesaj) {

            super(mesaj);
            this.camp = camp;
        }

        public String getCamp() {
            return camp;
        }
    }
}