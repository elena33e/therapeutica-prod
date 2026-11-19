package com.therapeutica.therapeutica_app.admin;

import com.therapeutica.therapeutica_app.security.AccesContService;
import com.therapeutica.therapeutica_app.util.AccesInterzisException;
import com.therapeutica.therapeutica_app.utilizatori.RoleType;
import com.therapeutica.therapeutica_app.utilizatori.UtilizatoriRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.time.OffsetDateTime;
import java.util.UUID;

@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private final AccesContService accesContService;
    private final UtilizatoriRepository utilizatoriRepository;

    public record ContMedic(
            UUID id,
            String nume,
            String prenume,
            String email,
            OffsetDateTime creatLa
    ) {}

    @GetMapping
    public String paginaPrincipala() {
        var autor = accesContService.utilizatorCurent();

        if (autor.getRol() != RoleType.ADMIN) {
            throw new AccesInterzisException();
        }

        return "redirect:/admin/dashboard/" + autor.getId();
    }

    @GetMapping("/dashboard/{id}")
    public String dashboard(@PathVariable UUID id, Model model) {
        var autor = accesContService.utilizatorCurent();

        if (autor.getRol() != RoleType.ADMIN
                || !autor.getId().equals(id)) {

            throw new AccesInterzisException();
        }

        var medici = utilizatoriRepository
                .findByRolOrderByNumeAscPrenumeAsc(RoleType.MEDIC)
                .stream()
                .map(utilizator -> new ContMedic(
                        utilizator.getId(),
                        utilizator.getNume(),
                        utilizator.getPrenume(),
                        utilizator.getEmail(),
                        utilizator.getCreatedAt()
                ))
                .toList();

        model.addAttribute("medici", medici);
        return "admin/dashboard";
    }
}