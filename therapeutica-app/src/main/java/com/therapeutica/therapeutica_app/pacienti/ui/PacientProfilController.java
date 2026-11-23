package com.therapeutica.therapeutica_app.pacienti.ui;

import com.therapeutica.therapeutica_app.notificari.external.WhatsAppService;
import com.therapeutica.therapeutica_app.pacienti.Pacienti;
import com.therapeutica.therapeutica_app.pacienti.PacientiRepository;
import com.therapeutica.therapeutica_app.pacienti.ProfilPacientForm;
import com.therapeutica.therapeutica_app.pacienti.ProfilPacientService;
import com.therapeutica.therapeutica_app.pacienti.Sex;
import com.therapeutica.therapeutica_app.security.AccesContService;
import com.therapeutica.therapeutica_app.util.NotFoundException;
import com.therapeutica.therapeutica_app.utilizatori.RoleType;
import com.therapeutica.therapeutica_app.utilizatori.Utilizatori;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/pacient/profil")
@RequiredArgsConstructor
public class PacientProfilController {

    private final AccesContService accesContService;
    private final PacientiRepository pacientiRepository;
    private final ProfilPacientService profilPacientService;
    private final WhatsAppService whatsappService;

    @InitBinder("formular")
    public void configureazaFormular(WebDataBinder binder) {
        binder.setAllowedFields(
                "nume",
                "prenume",
                "email",
                "cnp",
                "dataNasterii",
                "sex",
                "inaltime",
                "greutate",
                "grupaSangvina"
        );

        binder.registerCustomEditor(
                String.class,
                new StringTrimmerEditor(false)
        );
    }

    @GetMapping("/{id}")
    public String vizualizareProfil(
            @PathVariable UUID id,
            Model model) {

        var utilizator = accesContService.verificaAccesProfil(
                id,
                RoleType.PACIENT
        );

        Pacienti pacient = pacientiRepository
                .findByUserIdWithFullData(id)
                .orElseThrow(() ->
                        new NotFoundException(
                                "Profilul pacientului nu a fost găsit."
                        )
                );

        model.addAttribute("pacientId", id.toString());
        model.addAttribute("utilizator", utilizator);
        model.addAttribute("pacient", pacient);
        model.addAttribute("pageTitle", "Profil Pacient");
        model.addAttribute("mode", "view");

        adaugaContactMedic(pacient, utilizator, model);

        return "pacient/profil";
    }

    @GetMapping("/{id}/editare")
    public String formularEditareProfil(
            @PathVariable UUID id,
            Model model) {

        var utilizator = accesContService.verificaAccesProfil(
                id,
                RoleType.PACIENT
        );

        Pacienti pacient = incarcaPacient(id);

        ProfilPacientForm formular = new ProfilPacientForm();
        formular.setNume(utilizator.getNume());
        formular.setPrenume(utilizator.getPrenume());
        formular.setEmail(utilizator.getEmail());
        formular.setCnp(pacient.getCnp());
        formular.setDataNasterii(pacient.getDataNasterii());
        formular.setSex(
                pacient.getSex() == null
                        ? Sex.NEPRECIZAT
                        : pacient.getSex()
        );
        formular.setInaltime(pacient.getInaltime());
        formular.setGreutate(pacient.getGreutate());
        formular.setGrupaSangvina(pacient.getGrupaSangvina());

        model.addAttribute("formular", formular);

        return afiseazaEditare(id, model);
    }

    @PostMapping("/{id}/salvare")
    public String salvareProfil(
            @PathVariable UUID id,
            @Valid @ModelAttribute("formular")
            ProfilPacientForm formular,
            BindingResult bindingResult,
            HttpSession session,
            RedirectAttributes redirectAttributes,
            Model model) {

        if (bindingResult.hasErrors()) {
            accesContService.verificaAccesProfil(
                    id,
                    RoleType.PACIENT
            );

            incarcaPacient(id);

            return afiseazaEditare(id, model);
        }

        ProfilPacientService.ProfilSalvat rezultat;

        try {
            rezultat = profilPacientService.salveaza(id, formular);
        } catch (ProfilPacientService.CampProfilInvalidException e) {
            bindingResult.rejectValue(
                    e.getCamp(),
                    "profil." + e.getCamp(),
                    e.getMessage()
            );

            return afiseazaEditare(id, model);
        }

        // Serviciul a revenit după confirmarea tranzacției.
        session.setAttribute("userNume", rezultat.nume());
        session.setAttribute("userPrenume", rezultat.prenume());
        session.setAttribute("userEmail", rezultat.email());

        redirectAttributes.addFlashAttribute(
                "successMessage",
                "Profilul medical a fost actualizat cu succes!"
        );

        return "redirect:/pacient/profil/" + rezultat.userId();
    }

    private Pacienti incarcaPacient(UUID userId) {
        return pacientiRepository.findByUserId(userId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "Profilul pacientului nu a fost găsit."
                        )
                );
    }

    private String afiseazaEditare(UUID userId, Model model) {
        model.addAttribute("pacientId", userId.toString());
        model.addAttribute("pageTitle", "Editare Profil Pacient");
        model.addAttribute("mode", "edit");
        model.addAttribute("sexe", Sex.values());
        model.addAttribute(
                "grupeSangvine",
                ProfilPacientForm.GRUPE_SANGVINE
        );

        return "pacient/profil";
    }

    private void adaugaContactMedic(
            Pacienti pacient,
            Utilizatori utilizator,
            Model model) {

        var medic = pacient.getMedic();

        if (medic == null || medic.getTelefon() == null) {
            return;
        }

        String numeMedic = medic.getUser() == null
                ? ""
                : medic.getUser().getNume();

        String mesaj = "Bună ziua, Dr. " + numeMedic
                + ". Vă contactez din portalul Therapeutica. "
                + "Sunt pacientul dumneavoastră, "
                + utilizator.getNume() + " "
                + utilizator.getPrenume() + ".";

        model.addAttribute(
                "whatsappLinkMedic",
                whatsappService.genereazaLinkContactPersonalizat(
                        medic.getTelefon(),
                        mesaj
                )
        );
    }
}