package com.therapeutica.therapeutica_app.notificari.ui;

import com.therapeutica.therapeutica_app.notificari.NotificariService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class NotificariController {

    private final NotificariService notificariService;

    @GetMapping("/medic/notificari/{medicId}")
    public String afiseazaInboxMedic(
            @PathVariable UUID medicId,
            Model model) {

        model.addAttribute(
                "notificari",
                notificariService.listaPentruUtilizator(medicId)
        );
        model.addAttribute("medicId", medicId);

        return "medic/notificari";
    }

    @GetMapping("/pacient/notificari/{pacientId}")
    public String afiseazaInboxPacient(
            @PathVariable UUID pacientId,
            Model model) {

        model.addAttribute(
                "notificari",
                notificariService.listaPentruUtilizator(pacientId)
        );
        model.addAttribute("pacientId", pacientId);

        return "pacient/notificari";
    }
}