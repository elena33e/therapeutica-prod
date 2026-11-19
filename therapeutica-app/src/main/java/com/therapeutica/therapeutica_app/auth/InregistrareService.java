package com.therapeutica.therapeutica_app.auth;

import com.therapeutica.therapeutica_app.auth.dto.InregistrareRequest;
import com.therapeutica.therapeutica_app.auth.dto.InregistrareResponse;
import com.therapeutica.therapeutica_app.cod_inregistrare.CodInregistrare;
import com.therapeutica.therapeutica_app.cod_inregistrare.CodInregistrareRepository;
import com.therapeutica.therapeutica_app.cod_inregistrare.PoliticaInvitatii;
import com.therapeutica.therapeutica_app.medici.Medici;
import com.therapeutica.therapeutica_app.medici.MediciRepository;
import com.therapeutica.therapeutica_app.pacienti.Pacienti;
import com.therapeutica.therapeutica_app.pacienti.PacientiRepository;
import com.therapeutica.therapeutica_app.pacienti.Sex;
import com.therapeutica.therapeutica_app.util.PasswordValidator;
import com.therapeutica.therapeutica_app.utilizatori.RoleType;
import com.therapeutica.therapeutica_app.utilizatori.Utilizatori;
import com.therapeutica.therapeutica_app.utilizatori.UtilizatoriRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class InregistrareService {

    private final CodInregistrareRepository codInregistrareRepository;
    private final UtilizatoriRepository utilizatoriRepository;
    private final PacientiRepository pacientiRepository;
    private final MediciRepository mediciRepository;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public InregistrareResponse inregistreazaUtilizator(
            InregistrareRequest request) {

        if (!PasswordValidator.isValida(request.getParola())) {
            return eroare(
                    "Parola trebuie să aibă cel puțin 8 caractere, o cifră și un caracter special."
            );
        }

        if (!request.getParola().equals(request.getConfirmaParola())) {
            return eroare("Parolele nu coincid.");
        }

        String codPrimit = text(request.getCodUnic())
                .toUpperCase(Locale.ROOT);

        String cnp = text(request.getCnp());

        if (codPrimit.isEmpty() || !cnp.matches("[0-9]{13}")) {
            return eroare("Verifică codul de înregistrare și CNP-ul.");
        }

        var codOpt = codInregistrareRepository
                .findByCodUnicForUpdate(codPrimit);

        if (codOpt.isEmpty()
                || codOpt.get().getStatus()
                != CodInregistrare.StatusCod.NEUTILIZAT) {

            return eroare("Cod invalid sau deja utilizat.");
        }

        CodInregistrare cod = codOpt.get();

        if (!cnp.equals(text(cod.getCnpDestinatar()))) {
            return eroare(
                    "CNP-ul nu corespunde celui asociat invitației."
            );
        }

        if (cod.getGeneratDe() == null
                || !PoliticaInvitatii.permite(
                cod.getGeneratDe().getRol(),
                cod.getRolDestinatar()
        )) {

            return eroare(
                    "Invitația nu mai corespunde regulilor de acces. Solicită un cod nou."
            );
        }

        if (text(cod.getEmailDestinatar()).isEmpty()
                || text(cod.getNumeDestinatar()).isEmpty()
                || text(cod.getPrenumeDestinatar()).isEmpty()) {

            return eroare("Invitația conține date incomplete.");
        }

        if (utilizatoriRepository.existsByEmailIgnoreCase(
                cod.getEmailDestinatar())) {

            return eroare("Există deja un cont cu acest email.");
        }

        Medici medicAlPacientului = null;

        if (cod.getRolDestinatar() == RoleType.PACIENT) {
            medicAlPacientului = mediciRepository
                    .findById(cod.getGeneratDe().getId())
                    .orElse(null);

            if (medicAlPacientului == null) {
                return eroare("Medicul asociat invitației nu este disponibil.");
            }

            if (pacientiRepository.existsByCnp(cnp)) {
                return eroare("Există deja un pacient cu acest CNP.");
            }
        }

        Utilizatori utilizator = new Utilizatori();
        utilizator.setEmail(text(cod.getEmailDestinatar()));
        utilizator.setNume(text(cod.getNumeDestinatar()));
        utilizator.setPrenume(text(cod.getPrenumeDestinatar()));
        utilizator.setRol(cod.getRolDestinatar());
        utilizator.setParola(
                passwordEncoder.encode(request.getParola())
        );

        utilizatoriRepository.saveAndFlush(utilizator);

        if (cod.getRolDestinatar() == RoleType.MEDIC) {
            Medici medic = new Medici();
            medic.setUser(utilizator);
            medic.setSpecializare("");
            medic.setTelefon(text(cod.getTelefonDestinatar()));

            mediciRepository.saveAndFlush(medic);
        } else {
            Pacienti pacient = new Pacienti();
            pacient.setUser(utilizator);
            pacient.setCnp(cnp);
            pacient.setSex(Sex.NEPRECIZAT);
            pacient.setMedic(medicAlPacientului);

            pacientiRepository.saveAndFlush(pacient);
        }

        cod.setStatus(CodInregistrare.StatusCod.UTILIZAT);
        cod.setAtribuit(utilizator);
        codInregistrareRepository.save(cod);

        return new InregistrareResponse(
                true,
                "Cont creat și activat cu succes!",
                utilizator.getEmail(),
                utilizator.getRol(),
                false
        );
    }

    private static InregistrareResponse eroare(String mesaj) {
        return new InregistrareResponse(false, mesaj);
    }

    private static String text(String valoare) {
        return valoare == null ? "" : valoare.trim();
    }
}