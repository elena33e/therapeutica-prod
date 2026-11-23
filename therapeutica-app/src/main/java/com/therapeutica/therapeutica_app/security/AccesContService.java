package com.therapeutica.therapeutica_app.security;

import com.therapeutica.therapeutica_app.util.AccesInterzisException;
import com.therapeutica.therapeutica_app.utilizatori.RoleType;
import com.therapeutica.therapeutica_app.utilizatori.Utilizatori;
import com.therapeutica.therapeutica_app.utilizatori.UtilizatoriRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AccesContService {

    private final UtilizatoriRepository utilizatoriRepository;

    public Utilizatori utilizatorCurent() {
        var autentificare = SecurityContextHolder.getContext()
                .getAuthentication();

        if (autentificare == null
                || !autentificare.isAuthenticated()
                || !(autentificare.getPrincipal()
                instanceof UtilizatorAutentificat identitate)) {

            throw new AccesInterzisException(
                    "Este necesară reautentificarea."
            );
        }

        Utilizatori utilizator = utilizatoriRepository
                .findById(identitate.getUserId())
                .orElseThrow(() ->
                        new AccesInterzisException("Cont indisponibil.")
                );

        String autoritateNecesara = "ROLE_" + utilizator.getRol().name();

        boolean rolValid = autentificare.getAuthorities().stream()
                .anyMatch(autoritate ->
                        autoritateNecesara.equals(autoritate.getAuthority())
                );

        if (!rolValid) {
            throw new AccesInterzisException(
                    "Permisiunile contului s-au modificat. Reautentifică-te."
            );
        }

        return utilizator;
    }

    public Utilizatori verificaAccesProfil(
            UUID userIdDinUrl,
            RoleType rolNecesar) {

        Utilizatori utilizator = utilizatorCurent();

        if (utilizator.getRol() != rolNecesar
                || !utilizator.getId().equals(userIdDinUrl)) {

            throw new AccesInterzisException(
                    "Nu ai acces la acest profil."
            );
        }

        return utilizator;
    }
}