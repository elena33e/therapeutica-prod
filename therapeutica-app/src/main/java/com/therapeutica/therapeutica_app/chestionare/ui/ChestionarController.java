package com.therapeutica.therapeutica_app.chestionare.ui;

import com.therapeutica.therapeutica_app.audit.AuditLog;
import com.therapeutica.therapeutica_app.audit.AuditService;
import com.therapeutica.therapeutica_app.categorii_chestionare.CategoriiChestionare;
import com.therapeutica.therapeutica_app.categorii_chestionare.CategoriiChestionareRepository;
import com.therapeutica.therapeutica_app.chestionare.Chestionare;
import com.therapeutica.therapeutica_app.chestionare.ChestionareRepository;
import com.therapeutica.therapeutica_app.intrebari.Intrebare;
import com.therapeutica.therapeutica_app.intrebari.IntrebariRepository;
import com.therapeutica.therapeutica_app.pacienti.Pacienti;
import com.therapeutica.therapeutica_app.pacienti.PacientiRepository;
import com.therapeutica.therapeutica_app.raport_chestionar.RezultatScoring;
import com.therapeutica.therapeutica_app.raport_chestionar.ScoringService;
import com.therapeutica.therapeutica_app.raspunsuri_chestionare.RaspunsuriChestionare;
import com.therapeutica.therapeutica_app.raspunsuri_chestionare.RaspunsuriChestionareRepository;
import com.therapeutica.therapeutica_app.raspunsuri_intrebari.RaspunsuriIntrebari;
import com.therapeutica.therapeutica_app.raspunsuri_intrebari.RaspunsuriIntrebariService;
import com.therapeutica.therapeutica_app.util.NotFoundException;
import com.therapeutica.therapeutica_app.security.AccesChestionarService;
import com.therapeutica.therapeutica_app.chestionare.RevizuireChestionarService;
import com.therapeutica.therapeutica_app.raspunsuri_intrebari.ValidareRaspunsuriService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import com.therapeutica.therapeutica_app.security.AccesPacientService;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import com.therapeutica.therapeutica_app.chestionare.PregatireRaportService;
import com.therapeutica.therapeutica_app.security.AccesContService;
import com.therapeutica.therapeutica_app.util.AccesInterzisException;


@Controller
@RequestMapping("/chestionare")
@RequiredArgsConstructor
@Slf4j
public class ChestionarController {

    private final ChestionareRepository chestionareRepository;
    private final CategoriiChestionareRepository categoriiChestionareRepository;
    private final IntrebariRepository intrebariRepository;
    private final RaspunsuriChestionareRepository raspunsuriChestionareRepository;
    private final RaspunsuriIntrebariService raspunsuriIntrebariService;
    private final PacientiRepository pacientiRepository;
    private final ScoringService scoringService;
    private final AuditService auditService;
    private final AccesPacientService accesPacientService;
    private final AccesChestionarService accesChestionarService;
    private final RevizuireChestionarService revizuireChestionarService;
    private final ValidareRaspunsuriService validareRaspunsuriService;
    private final AccesContService accesContService;
    private final PregatireRaportService pregatireRaportService;

    /**
     * Afișează un chestionar pentru completare
     */
    @GetMapping("/completare/{raspunsChestionarId}")
    public String afiseazaPentruCompletare(
            @PathVariable UUID raspunsChestionarId,
            HttpSession session,
            HttpServletResponse response,
            Model model) {

        log.info("Accesare chestionar pentru completare: {}", raspunsChestionarId);

        RaspunsuriChestionare raspunsChestionar =
                accesChestionarService.incarcaPentruPacient(
                        session,
                        raspunsChestionarId
                );

        try {
            if (raspunsChestionar.getChestionar() == null) {
                log.error("RaspunsuriChestionare {} nu are chestionar asociat!", raspunsChestionarId);
                model.addAttribute("error", "Chestionarul nu are date asociate");
                return "error";
            }

            Pacienti pacient = raspunsChestionar.getPacient();

            auditService.inregistreaza(
                    auditService.utilizatorCurent(session),
                    AuditLog.ActiuneAudit.VIZUALIZARE,
                    AuditLog.TipResursa.CHESTIONAR,
                    raspunsChestionarId,
                    pacient != null ? pacient.getId() : null
            );

            if (raspunsChestionar.getStatus()
                    == RaspunsuriChestionare.StatusRaspuns.COMPLETAT
                    || raspunsChestionar.getStatus()
                    == RaspunsuriChestionare.StatusRaspuns.REVIZUIT) {

                return "redirect:/chestionare/pacient/"
                        + session.getAttribute("userId")
                        + "/istoric";
            }

            // Sexul pacientului determină ce categorii sunt afișate
            String sexPacient = null;
            if (pacient != null && pacient.getSex() != null) {
                sexPacient = pacient.getSex().name();
            }

            Chestionare chestionar = raspunsChestionar.getChestionar();

            List<CategoriiChestionare> toateCategoriile = categoriiChestionareRepository
                    .findByChestionarId(chestionar.getId());

            List<CategoriiChestionare> categoriiFiltrate = filtreazaCategoriiDupaSex(toateCategoriile, sexPacient);

            log.info("Chestionar '{}': {} categorii din {} după filtrarea pe sex",
                    chestionar.getNume(), categoriiFiltrate.size(), toateCategoriile.size());

            Map<UUID, List<Intrebare>> intrebariPeCategorii = new LinkedHashMap<>();
            for (CategoriiChestionare categorie : categoriiFiltrate) {
                List<Intrebare> intrebari = intrebariRepository.findByCategorieId(categorie.getId());
                intrebariPeCategorii.put(categorie.getId(), intrebari);
            }

            model.addAttribute("chestionar", chestionar);
            model.addAttribute("raspunsChestionar", raspunsChestionar);
            model.addAttribute("categorii", categoriiFiltrate);
            model.addAttribute("intrebariPeCategorii", intrebariPeCategorii);

            Map<UUID, List<String>> optiuniPeIntrebare = new LinkedHashMap<>();

            for (List<Intrebare> intrebari : intrebariPeCategorii.values()) {
                for (Intrebare intrebare : intrebari) {
                    if (intrebare.getTipIntrebare()
                            == Intrebare.TipIntrebare.MULTIPLE_CHOICE) {

                        optiuniPeIntrebare.put(
                                intrebare.getId(),
                                validareRaspunsuriService.citesteOptiuni(intrebare)
                        );
                    }
                }
            }

            model.addAttribute("optiuniPeIntrebare", optiuniPeIntrebare);

            if (!model.containsAttribute("raspunsuriFormular")) {
                model.addAttribute("raspunsuriFormular", Map.of());
            }

            return "pacient/completare-chestionar";

        } catch (Exception e) {
            log.error(
                    "Eroare la afișarea chestionarului {}",
                    raspunsChestionarId,
                    e
            );

            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());

            model.addAttribute(
                    "error",
                    "Chestionarul nu poate fi afișat momentan. Încearcă din nou."
            );

            return "error";
        }
    }

    @GetMapping("/pacient/{userId}/disponibile")
    public String listeazaDisponibile(
            @PathVariable UUID userId,
            HttpSession session,
            Model model) {

        return pregatesteListaPacient(userId, session, model, "disponibile");
    }

    @GetMapping("/pacient/{userId}/istoric")
    public String afiseazaIstoric(
            @PathVariable UUID userId,
            HttpSession session,
            Model model) {

        return pregatesteListaPacient(userId, session, model, "istoric");
    }

    private String pregatesteListaPacient(
            UUID userId,
            HttpSession session,
            Model model,
            String activeTab) {

        accesPacientService.verificaAccesPropriuByUserId(session, userId);

        Pacienti pacient = pacientiRepository.findByUserId(userId)
                .orElseThrow(() ->
                        new NotFoundException("Profilul pacientului nu există."));

        List<RaspunsuriChestionare> disponibile =
                raspunsuriChestionareRepository
                        .findByPacientIdAndStatusFullRelations(
                                pacient.getId(),
                                RaspunsuriChestionare.StatusRaspuns.NECOMPLETAT
                        );

        List<RaspunsuriChestionare> istoric =
                raspunsuriChestionareRepository
                        .findByPacientIdAndStatusInFullRelations(
                                pacient.getId(),
                                List.of(
                                        RaspunsuriChestionare.StatusRaspuns.COMPLETAT,
                                        RaspunsuriChestionare.StatusRaspuns.REVIZUIT
                                )
                        );

        model.addAttribute("chestionareDisponibile", disponibile);
        model.addAttribute("istoricChestionare", istoric);
        model.addAttribute("pacientId", pacient.getId());
        model.addAttribute("userId", userId);
        model.addAttribute("activeTab", activeTab);

        return "pacient/chestionare";
    }
    // ========== PENTRU MEDIC ==========

    /**
     * Vizualizează răspunsurile unui pacient (pentru medic).
     * Rută unică pentru afișarea rezultatelor — folosită atât din profilul
     * pacientului, cât și din notificarea de chestionar completat.
     */
    @GetMapping("/medic/vizualizare/{raspunsChestionarId}")
    public String vizualizeazaRaspunsuri(
            @PathVariable UUID raspunsChestionarId,
            HttpSession session,
            HttpServletResponse response,
            Model model) {

        var utilizatorCurent = accesContService.utilizatorCurent();
        RaspunsuriChestionare raspunsChestionar =
                raspunsuriChestionareRepository
                        .findByIdForCompletare(raspunsChestionarId)
                        .orElseThrow(() ->
                                new NotFoundException(
                                        "Chestionarul nu a fost găsit."
                                )
                        );

        Pacienti pacient = raspunsChestionar.getPacient();

        if (pacient == null) {
            throw new NotFoundException(
                    "Pacientul asociat nu a fost găsit."
            );
        }

        accesPacientService.verificaAccesMedic(session, pacient.getId());

        boolean finalizat =
                raspunsChestionar.getStatus()
                        == RaspunsuriChestionare.StatusRaspuns.COMPLETAT
                        || raspunsChestionar.getStatus()
                        == RaspunsuriChestionare.StatusRaspuns.REVIZUIT;

        if (!finalizat) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Raportul este disponibil după completarea chestionarului."
            );
        }

        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");

        Optional<RezultatScoring> scorExistent = scoringService
                .getScoruriCalculate(raspunsChestionarId)
                .filter(scor ->
                        Boolean.FALSE.equals(scor.getNecesitaRecalculare())
                );

        if (scorExistent.isEmpty()) {
            model.addAttribute(
                    "raspunsChestionarId",
                    raspunsChestionarId
            );

            return "medic/pregatire-raport";
        }

        RezultatScoring rezultat = scorExistent.get();

        List<RaspunsuriIntrebari> raspunsuriDetaliate =
                raspunsuriIntrebariService.getRaspunsuriDetaliate(
                        raspunsChestionarId
                );

        Map<String, List<RaspunsuriIntrebari>> raspunsuriPeCategorii =
                new LinkedHashMap<>();

        Map<String, CategoriiChestionare> categoriiMap =
                new LinkedHashMap<>();

        for (RaspunsuriIntrebari raspuns : raspunsuriDetaliate) {
            CategoriiChestionare categorie = raspuns.getCategorie();

            String numeCategorie = categorie != null
                    ? categorie.getNume()
                    : "Fără categorie";

            raspunsuriPeCategorii
                    .computeIfAbsent(
                            numeCategorie,
                            cheie -> new ArrayList<>()
                    )
                    .add(raspuns);

            if (categorie != null) {
                categoriiMap.putIfAbsent(numeCategorie, categorie);
            }
        }

        auditService.inregistreaza(
                utilizatorCurent.getId(),
                AuditLog.ActiuneAudit.VIZUALIZARE,
                AuditLog.TipResursa.CHESTIONAR,
                raspunsChestionarId,
                pacient.getId()
        );

        model.addAttribute("chestionar", raspunsChestionar);
        model.addAttribute("rezultat", rezultat);
        model.addAttribute("raspunsuriPeCategorii", raspunsuriPeCategorii);
        model.addAttribute("categoriiMap", categoriiMap);
        model.addAttribute("medicId", utilizatorCurent.getId());

        return "medic/vizualizare-rezultate";
    }

    /**
     * Afișează toate tipurile de chestionare disponibile în sistem (pentru medici).
     * Nu conține date de pacient, deci nu se auditează.
     */
    @GetMapping("/medic/lista-chestionare")
    public String listaChestionareSistem(Model model) {
        log.info("Medic accesează lista globală de chestionare");

        model.addAttribute("chestionareSistem", chestionareRepository.findAll());
        return "medic/chestionare/lista-chestionare";
    }

    /**
     * Vizualizează structura unui chestionar (șablon, fără date de pacient).
     * Nu se auditează.
     */
    @GetMapping("/medic/vizualizare-structura/{id}")
    public String vizualizareStructuraChestionar(@PathVariable UUID id, Model model) {
        log.info("Medic vizualizează structura chestionarului: {}", id);

        Chestionare chestionar = chestionareRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Chestionarul nu a fost găsit"));

        List<CategoriiChestionare> categorii = categoriiChestionareRepository.findByChestionarId(id);
        categorii.sort(comparatorDupaOrdine());

        Map<UUID, List<Intrebare>> intrebariPeCategorii = new LinkedHashMap<>();
        for (CategoriiChestionare categorie : categorii) {
            List<Intrebare> intrebari = intrebariRepository.findByCategorieId(categorie.getId());
            intrebariPeCategorii.put(categorie.getId(), intrebari);
        }

        model.addAttribute("chestionar", chestionar);
        model.addAttribute("categorii", categorii);
        model.addAttribute("intrebariPeCategorii", intrebariPeCategorii);
        model.addAttribute("esteVizualizareMedic", true);

        return "medic/chestionare/vizualizare-chestionar";
    }

    /**
     * Marchează un chestionar ca fiind revizuit de către medic
     */
    @PostMapping("/medic/marcheaza-revizuit/{raspunsChestionarId}")
    public String marcheazaCaRevizuit(
            @PathVariable UUID raspunsChestionarId,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        UUID utilizatorId = accesContService.utilizatorCurent().getId();

        var rezultat = revizuireChestionarService.marcheazaCaRevizuit(
                raspunsChestionarId,
                session
        );

        if (rezultat.modificat()) {
            auditService.inregistreaza(
                    utilizatorId,
                    AuditLog.ActiuneAudit.MODIFICARE,
                    AuditLog.TipResursa.CHESTIONAR,
                    raspunsChestionarId,
                    rezultat.pacientId()
            );
        }

        redirectAttributes.addFlashAttribute(
                "success",
                rezultat.modificat()
                        ? "Evaluarea a fost marcată ca revizuită."
                        : "Evaluarea era deja marcată ca revizuită."
        );

        return "redirect:/medic/"
                + utilizatorId
                + "/pacient/"
                + rezultat.pacientId();
    }


    // ========== METODE HELPER ==========

    /**
     * Păstrează categoriile potrivite pentru sexul pacientului.
     * Categoriile fără sex-țintă sau marcate AMBELE se aplică tuturor.
     */
    private List<CategoriiChestionare> filtreazaCategoriiDupaSex(List<CategoriiChestionare> categorii,
                                                                 String sexPacient) {
        List<CategoriiChestionare> rezultat = new ArrayList<>();

        for (CategoriiChestionare categorie : categorii) {
            if (validareRaspunsuriService.esteCategorieAplicabila(
                    categorie,
                    sexPacient)) {
                rezultat.add(categorie);
            }
        }

        rezultat.sort(comparatorDupaOrdine());
        return rezultat;
    }

    private Comparator<CategoriiChestionare> comparatorDupaOrdine() {
        return Comparator.comparingInt(c -> c.getOrdine() != null ? c.getOrdine() : 999);
    }

    /**
     * Cel mai recent completat primul; cele fără dată la final.
     */
    private Comparator<RaspunsuriChestionare> comparatorDupaDataCompletare() {
        return (rc1, rc2) -> {
            if (rc1.getCompletatLa() == null && rc2.getCompletatLa() == null) return 0;
            if (rc1.getCompletatLa() == null) return 1;
            if (rc2.getCompletatLa() == null) return -1;
            return rc2.getCompletatLa().compareTo(rc1.getCompletatLa());
        };
    }

    private UUID extragePacientId(UUID raspunsChestionarId) {
        return raspunsuriChestionareRepository.findById(raspunsChestionarId)
                .map(rc -> rc.getPacient() != null ? rc.getPacient().getId() : null)
                .orElse(null);
    }

    @PostMapping("/medic/pregateste-raport/{raspunsChestionarId}")
    public String pregatesteRaport(
            @PathVariable UUID raspunsChestionarId,
            HttpSession session,
            RedirectAttributes redirectAttributes) {

        UUID utilizatorId = accesContService.utilizatorCurent().getId();

        PregatireRaportService.RezultatPregatire rezultat;

        try {
            rezultat = pregatireRaportService.pregateste(
                    raspunsChestionarId,
                    session
            );
        } catch (AccesInterzisException
                 | NotFoundException
                 | ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error(
                    "Pregătirea raportului {} a eșuat",
                    raspunsChestionarId,
                    e
            );

            redirectAttributes.addFlashAttribute(
                    "error",
                    "Raportul nu a putut fi pregătit. Încearcă din nou."
            );

            return "redirect:/chestionare/medic/vizualizare/"
                    + raspunsChestionarId;
        }

        if (rezultat.modificat()) {
            auditService.inregistreaza(
                    utilizatorId,
                    AuditLog.ActiuneAudit.MODIFICARE,
                    AuditLog.TipResursa.CHESTIONAR,
                    raspunsChestionarId,
                    rezultat.pacientId()
            );
        }

        return "redirect:/chestionare/medic/vizualizare/"
                + raspunsChestionarId;
    }
}