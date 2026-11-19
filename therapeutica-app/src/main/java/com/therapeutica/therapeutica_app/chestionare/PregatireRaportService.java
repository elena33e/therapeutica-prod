package com.therapeutica.therapeutica_app.chestionare;

import com.therapeutica.therapeutica_app.raport_chestionar.RezultatScoringRepository;
import com.therapeutica.therapeutica_app.raport_chestionar.ScoringService;
import com.therapeutica.therapeutica_app.raspunsuri_chestionare.RaspunsuriChestionare;
import com.therapeutica.therapeutica_app.raspunsuri_chestionare.RaspunsuriChestionareRepository;
import com.therapeutica.therapeutica_app.raspunsuri_intrebari.RaspunsuriIntrebariRepository;
import com.therapeutica.therapeutica_app.security.AccesPacientService;
import com.therapeutica.therapeutica_app.util.NotFoundException;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PregatireRaportService {

    private final RaspunsuriChestionareRepository chestionareRepository;
    private final RaspunsuriIntrebariRepository raspunsuriRepository;
    private final RezultatScoringRepository rezultateRepository;
    private final AccesPacientService accesPacientService;
    private final ScoringService scoringService;

    public record RezultatPregatire(
            UUID pacientId,
            boolean modificat
    ) {}

    @Transactional
    public RezultatPregatire pregateste(
            UUID atribuireId,
            HttpSession session) {

        RaspunsuriChestionare atribuire = chestionareRepository
                .findByIdForUpdate(atribuireId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "Chestionarul nu a fost găsit."
                        )
                );

        if (atribuire.getPacient() == null) {
            throw new NotFoundException(
                    "Pacientul asociat nu a fost găsit."
            );
        }

        UUID pacientId = atribuire.getPacient().getId();

        accesPacientService.verificaAccesMedic(session, pacientId);

        boolean finalizat =
                atribuire.getStatus()
                        == RaspunsuriChestionare.StatusRaspuns.COMPLETAT
                        || atribuire.getStatus()
                        == RaspunsuriChestionare.StatusRaspuns.REVIZUIT;

        if (!finalizat) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Raportul poate fi pregătit după completarea chestionarului."
            );
        }

        boolean scorValid = rezultateRepository
                .findByRaspunsChestionarId(atribuireId)
                .filter(rezultat ->
                        Boolean.FALSE.equals(
                                rezultat.getNecesitaRecalculare()
                        )
                )
                .isPresent();

        if (scorValid) {
            return new RezultatPregatire(pacientId, false);
        }

        if (raspunsuriRepository.countByRaspunsChestionarId(atribuireId) == 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Chestionarul nu conține răspunsuri pentru raport."
            );
        }

        scoringService.calculeazaSiSalveazaScor(atribuireId);

        return new RezultatPregatire(pacientId, true);
    }
}