package com.therapeutica.therapeutica_app.admin;

import com.therapeutica.therapeutica_app.util.PasswordValidator;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdministratorInitialService {

    private final PasswordEncoder passwordEncoder;
    private final Validator validator;
    private final JdbcTemplate jdbc;

    private record DateAdministrator(
            @NotBlank @Email String email,
            @NotBlank String nume,
            @NotBlank String prenume
    ) {}

    @Transactional
    public boolean creeaza(
            String email,
            String nume,
            String prenume,
            String parola) {

        jdbc.execute("SET LOCAL lock_timeout = '10s'");

        jdbc.execute(
                "LOCK TABLE public.utilizatori IN SHARE ROW EXCLUSIVE MODE"
        );

        long administratori = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM public.utilizatori
                WHERE rol = 'ADMIN'
                """, Long.class);

        if (administratori > 0) {
            return false;
        }

        DateAdministrator date = new DateAdministrator(
                text(email),
                text(nume),
                text(prenume)
        );

        if (!validator.validate(date).isEmpty()
                || !PasswordValidator.isValida(parola)) {

            throw new IllegalStateException(
                    "Datele administratorului sunt incomplete sau invalide."
            );
        }

        long conturiCuAcelasiEmail = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM public.utilizatori
                WHERE LOWER(email) = LOWER(?)
                """, Long.class, date.email());

        if (conturiCuAcelasiEmail > 0) {
            throw new IllegalStateException(
                    "Email-ul administratorului este folosit de un cont existent."
            );
        }

        jdbc.update("""
                INSERT INTO public.utilizatori
                    (id, created_at, email, nume, prenume, parola, rol)
                VALUES (?, CURRENT_TIMESTAMP, ?, ?, ?, ?, 'ADMIN')
                """,
                UUID.randomUUID(),
                date.email(),
                date.nume(),
                date.prenume(),
                passwordEncoder.encode(parola)
        );

        return true;
    }

    private static String text(String valoare) {
        return valoare == null ? "" : valoare.trim();
    }
}