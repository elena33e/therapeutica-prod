package com.therapeutica.therapeutica_app.medici.ui;

import com.therapeutica.therapeutica_app.raspunsuri_chestionare.RaspunsuriChestionare;
import com.therapeutica.therapeutica_app.raspunsuri_chestionare.RaspunsuriChestionareRepository;
import com.therapeutica.therapeutica_app.security.AccesContService;
import com.therapeutica.therapeutica_app.util.AccesInterzisException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class RapoarteMedicController {

    private final AccesContService accesContService;
    private final RaspunsuriChestionareRepository chestionareRepository;

    @GetMapping("/medic/rapoarte/{medicId}")
    public String listaRapoarte(
            @PathVariable UUID medicId,
            HttpServletResponse response,
            Model model) {

        var utilizator = accesContService.utilizatorCurent();

        if (!"MEDIC".equals(utilizator.getRol().name())
                || !utilizator.getId().equals(medicId)) {

            throw new AccesInterzisException(
                    "Nu poți accesa rapoartele altui medic."
            );
        }

        response.setHeader("Cache-Control", "no-store");

        model.addAttribute(
                "rapoarte",
                chestionareRepository.findRapoartePentruMedic(
                        utilizator.getId(),
                        List.of(
                                RaspunsuriChestionare.StatusRaspuns.COMPLETAT,
                                RaspunsuriChestionare.StatusRaspuns.REVIZUIT
                        )
                )
        );

        return "medic/rapoarte";
    }
}