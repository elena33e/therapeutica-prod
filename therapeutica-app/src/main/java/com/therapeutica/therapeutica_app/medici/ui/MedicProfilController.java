package com.therapeutica.therapeutica_app.medici.ui;

import com.therapeutica.therapeutica_app.medici.Medici;
import com.therapeutica.therapeutica_app.medici.MediciRepository;
import com.therapeutica.therapeutica_app.medici.ProfilMedicForm;
import com.therapeutica.therapeutica_app.medici.ProfilMedicService;
import com.therapeutica.therapeutica_app.security.AccesContService;
import com.therapeutica.therapeutica_app.util.NotFoundException;
import com.therapeutica.therapeutica_app.utilizatori.RoleType;
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
@RequestMapping("/medic/profil")
@RequiredArgsConstructor
public class MedicProfilController {

    private final AccesContService accesContService;
    private final MediciRepository mediciRepository;
    private final ProfilMedicService profilMedicService;

    @InitBinder("formular")
    public void configureazaFormular(WebDataBinder binder) {
        binder.setAllowedFields(
                "nume",
                "prenume",
                "email",
                "specializare",
                "telefon"
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
                RoleType.MEDIC
        );

        Medici medic = incarcaMedic(id);

        model.addAttribute("medicId", id.toString());
        model.addAttribute("utilizator", utilizator);
        model.addAttribute("medic", medic);
        model.addAttribute("pageTitle", "Profil Medic");
        model.addAttribute("mode", "view");

        return "medic/profil";
    }

    @GetMapping("/{id}/editare")
    public String formularEditareProfil(
            @PathVariable UUID id,
            Model model) {

        var utilizator = accesContService.verificaAccesProfil(
                id,
                RoleType.MEDIC
        );

        Medici medic = incarcaMedic(id);

        ProfilMedicForm formular = new ProfilMedicForm();
        formular.setNume(utilizator.getNume());
        formular.setPrenume(utilizator.getPrenume());
        formular.setEmail(utilizator.getEmail());
        formular.setSpecializare(medic.getSpecializare());
        formular.setTelefon(medic.getTelefon());

        model.addAttribute("formular", formular);

        return afiseazaEditare(id, model);
    }

    @PostMapping("/{id}/salvare")
    public String salvareProfil(
            @PathVariable UUID id,
            @Valid @ModelAttribute("formular")
            ProfilMedicForm formular,
            BindingResult bindingResult,
            HttpSession session,
            RedirectAttributes redirectAttributes,
            Model model) {

        if (bindingResult.hasErrors()) {
            accesContService.verificaAccesProfil(
                    id,
                    RoleType.MEDIC
            );

            incarcaMedic(id);

            return afiseazaEditare(id, model);
        }

        ProfilMedicService.ProfilSalvat rezultat;

        try {
            rezultat = profilMedicService.salveaza(id, formular);
        } catch (ProfilMedicService.EmailDejaFolositException e) {
            bindingResult.rejectValue(
                    "email",
                    "email.dejaFolosit",
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
                "Profilul a fost actualizat!"
        );

        return "redirect:/medic/profil/" + rezultat.userId();
    }

    private Medici incarcaMedic(UUID userId) {
        return mediciRepository.findByUserId(userId)
                .orElseThrow(() ->
                        new NotFoundException(
                                "Profilul medicului nu a fost găsit."
                        )
                );
    }

    private String afiseazaEditare(UUID userId, Model model) {
        model.addAttribute("medicId", userId.toString());
        model.addAttribute("pageTitle", "Editare Profil Medic");
        model.addAttribute("mode", "edit");

        return "medic/profil";
    }
}