package com.therapeutica.therapeutica_app.cod_inregistrare.ui;

import com.therapeutica.therapeutica_app.cod_inregistrare.CodInregistrareService;
import com.therapeutica.therapeutica_app.cod_inregistrare.dto.GenerareCodRequest;
import com.therapeutica.therapeutica_app.cod_inregistrare.dto.GenerareCodResponse;
import com.therapeutica.therapeutica_app.security.AccesContService;
import com.therapeutica.therapeutica_app.util.AccesInterzisException;
import com.therapeutica.therapeutica_app.utilizatori.RoleType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/generare-cod")
@RequiredArgsConstructor
public class GenerareCodController {

    private final CodInregistrareService codInregistrareService;
    private final AccesContService accesContService;

    @InitBinder("request")
    void configureazaFormularul(WebDataBinder binder) {
        binder.setAllowedFields(
                "emailDestinatar",
                "cnpDestinatar",
                "telefonDestinatar",
                "rolDestinatar",
                "numeDestinatar",
                "prenumeDestinatar"
        );
    }

    @GetMapping
    public String afiseazaPagina(Model model) {
        model.addAttribute("request", formularNou());
        adaugaDatelePaginii(model);
        return "generare-cod";
    }

    @PostMapping
    public String generareCod(
            @ModelAttribute("request") GenerareCodRequest request,
            BindingResult bindingResult,
            Model model) {

        GenerareCodResponse rezultat;

        if (bindingResult.hasErrors()) {
            rezultat = new GenerareCodResponse(
                    "Eroare: Verifică datele formularului."
            );
        } else {
            try {
                rezultat = codInregistrareService
                        .generareCodInregistrare(request);
            } catch (AccesInterzisException | ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                rezultat = new GenerareCodResponse(
                        "Eroare: Invitația nu a putut fi salvată."
                );
            }
        }

        model.addAttribute("rezultat", rezultat);

        if (rezultat.getCodUnic() != null) {
            model.addAttribute("request", formularNou());
        }

        adaugaDatelePaginii(model);
        return "generare-cod";
    }

    @PostMapping("/{id}/anuleaza")
    public String anuleazaInvitatia(
            @PathVariable UUID id,
            RedirectAttributes redirectAttributes) {

        codInregistrareService.anuleazaInvitatia(id);
        redirectAttributes.addFlashAttribute(
                "success",
                "Invitația a fost anulată."
        );

        return "redirect:/generare-cod";
    }

    private GenerareCodRequest formularNou() {
        GenerareCodRequest request = new GenerareCodRequest();
        request.setRolDestinatar(
                codInregistrareService.getRolDestinatarPermis()
        );
        return request;
    }

    private void adaugaDatelePaginii(Model model) {
        var autor = accesContService.utilizatorCurent();
        RoleType destinatar =
                codInregistrareService.getRolDestinatarPermis();

        model.addAttribute(
                "tipCont",
                destinatar == RoleType.MEDIC ? "medic" : "pacient"
        );
        model.addAttribute(
                "dashboardUrl",
                (autor.getRol() == RoleType.ADMIN
                        ? "/admin/dashboard/"
                        : "/medic/dashboard/") + autor.getId()
        );
        model.addAttribute(
                "coduri",
                codInregistrareService.getCoduriGestionabile()
        );
    }
}