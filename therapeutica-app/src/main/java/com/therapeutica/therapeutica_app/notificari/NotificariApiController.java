package com.therapeutica.therapeutica_app.notificari;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.view.RedirectView;

import java.util.UUID;

@RestController
@RequestMapping("/api/notificari")
@RequiredArgsConstructor
public class NotificariApiController {

    private final NotificariService notificariService;

    @GetMapping("/necitite-count/{userId}")
    public long getNumarNotificariNecitite(
            @PathVariable UUID userId) {

        return notificariService.numarNecitite(userId);
    }

    @PostMapping("/acceseaza/{id}")
    public RedirectView acceseazaNotificare(
            @PathVariable Long id) {

        return new RedirectView(notificariService.acceseaza(id));
    }
}