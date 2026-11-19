package com.therapeutica.therapeutica_app.security;

import com.therapeutica.therapeutica_app.pacienti.Pacienti;
import com.therapeutica.therapeutica_app.pacienti.PacientiRepository;
import com.therapeutica.therapeutica_app.util.AccesInterzisException;
import com.therapeutica.therapeutica_app.utilizatori.Utilizatori;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccesPacientService {

    private final PacientiRepository pacientiRepository;
    private final AccesContService accesContService;

    public void verificaAccesMedic(HttpSession session, UUID pacientId) {
        UUID medicId = utilizatorCuRol("MEDIC").getId();

        Pacienti pacient = pacientiRepository.findByIdWithUser(pacientId)
                .orElseThrow(() ->
                        new AccesInterzisException("Acces indisponibil.")
                );

        if (pacient.getMedic() == null
                || !medicId.equals(pacient.getMedic().getUserId())) {

            throw new AccesInterzisException(
                    "Nu ai acces la datele acestui pacient."
            );
        }
    }

    public void verificaAccesPropriu(HttpSession session, UUID pacientId) {
        UUID userId = utilizatorCuRol("PACIENT").getId();

        Pacienti pacient = pacientiRepository.findByUserId(userId)
                .orElseThrow(() ->
                        new AccesInterzisException("Profil indisponibil.")
                );

        if (!pacient.getId().equals(pacientId)) {
            throw new AccesInterzisException(
                    "Nu ai acces la datele acestui pacient."
            );
        }
    }

    public void verificaAccesPropriuByUserId(
            HttpSession session,
            UUID userIdDinUrl) {

        UUID userId = utilizatorCuRol("PACIENT").getId();

        if (!userId.equals(userIdDinUrl)) {
            throw new AccesInterzisException(
                    "Nu ai acces la datele acestui utilizator."
            );
        }
    }

    private Utilizatori utilizatorCuRol(String rolNecesar) {
        Utilizatori utilizator = accesContService.utilizatorCurent();

        if (!rolNecesar.equals(utilizator.getRol().name())) {
            throw new AccesInterzisException(
                    "Contul nu are permisiunea necesară."
            );
        }

        return utilizator;
    }
}