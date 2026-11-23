package com.therapeutica.therapeutica_app.medici;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ProfilMedicForm {

    @NotBlank(message = "Numele este obligatoriu.")
    private String nume;

    @NotBlank(message = "Prenumele este obligatoriu.")
    private String prenume;

    @NotBlank(message = "Emailul este obligatoriu.")
    @Email(message = "Formatul emailului este invalid.")
    private String email;

    private String specializare;

    @Size(
            max = 20,
            message = "Telefonul poate avea maximum 20 de caractere."
    )
    private String telefon;
}