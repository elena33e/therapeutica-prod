package com.therapeutica.therapeutica_app.notificari;

import com.therapeutica.therapeutica_app.security.AccesContService;
import com.therapeutica.therapeutica_app.util.AccesInterzisException;
import com.therapeutica.therapeutica_app.util.NotFoundException;
import com.therapeutica.therapeutica_app.utilizatori.Utilizatori;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class NotificariService {

    private final NotificareRepository notificareRepository;
    private final AccesContService accesContService;

    public List<Notificare> listaPentruUtilizator(UUID utilizatorSolicitat) {
        UUID utilizatorCurent = verificaProprietarul(utilizatorSolicitat);

        return notificareRepository
                .findByUtilizatorDestinatar_IdOrderByDataCreareDesc(
                        utilizatorCurent
                );
    }

    public long numarNecitite(UUID utilizatorSolicitat) {
        UUID utilizatorCurent = verificaProprietarul(utilizatorSolicitat);

        return notificareRepository
                .countByUtilizatorDestinatar_IdAndCititaFalse(
                        utilizatorCurent
                );
    }

    @Transactional
    public String acceseaza(Long notificareId) {
        Utilizatori utilizator = utilizatorCurentPermis();

        Notificare notificare = notificareRepository
                .findByIdAndUtilizatorDestinatar_Id(
                        notificareId,
                        utilizator.getId()
                )
                .orElseThrow(() ->
                        new NotFoundException("Notificare indisponibilă.")
                );

        String destinatie = destinatieInterna(
                notificare.getLinkActiune(),
                utilizator
        );

        notificare.setCitita(true);

        return destinatie;
    }

    private UUID verificaProprietarul(UUID utilizatorSolicitat) {
        UUID utilizatorCurent = utilizatorCurentPermis().getId();

        if (!utilizatorCurent.equals(utilizatorSolicitat)) {
            throw new AccesInterzisException(
                    "Nu poți accesa notificările altui utilizator."
            );
        }

        return utilizatorCurent;
    }

    private Utilizatori utilizatorCurentPermis() {
        Utilizatori utilizator = accesContService.utilizatorCurent();
        String rol = utilizator.getRol().name();

        if (!"MEDIC".equals(rol) && !"PACIENT".equals(rol)) {
            throw new AccesInterzisException(
                    "Contul nu are acces la notificări."
            );
        }

        return utilizator;
    }

    private String destinatieInterna(
            String link,
            Utilizatori utilizator) {

        if (link != null
                && link.startsWith("/")
                && !link.startsWith("//")
                && !link.contains("\\")) {

            try {
                URI uri = URI.create(link);

                if (!uri.isAbsolute()
                        && uri.getRawAuthority() == null
                        && uri.getRawPath() != null
                        && uri.getRawPath().startsWith("/")) {
                    return link;
                }
            } catch (IllegalArgumentException ignored) {
                // Un link invalid revine la inbox-ul propriu.
            }
        }

        String prefix = utilizator.getRol().name()
                .toLowerCase(Locale.ROOT);

        return "/" + prefix + "/notificari/" + utilizator.getId();
    }
}