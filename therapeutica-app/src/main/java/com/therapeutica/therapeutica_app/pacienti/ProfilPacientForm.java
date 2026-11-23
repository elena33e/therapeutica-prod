package com.therapeutica.therapeutica_app.pacienti;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
public class ProfilPacientForm {

    public static final List<String> GRUPE_SANGVINE = List.of(
            "0 (I) Rh+",
            "0 (I) Rh-",
            "A (II) Rh+",
            "A (II) Rh-",
            "B (III) Rh+",
            "B (III) Rh-",
            "AB (IV) Rh+",
            "AB (IV) Rh-"
    );

    @NotBlank(message = "Numele este obligatoriu.")
    private String nume;

    @NotBlank(message = "Prenumele este obligatoriu.")
    private String prenume;

    @NotBlank(message = "Emailul este obligatoriu.")
    @Email(message = "Formatul emailului este invalid.")
    private String email;

    @NotBlank(message = "CNP-ul este obligatoriu.")
    @Size(
            min = 13,
            max = 13,
            message = "CNP-ul trebuie să conțină exact 13 cifre."
    )
    private String cnp;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate dataNasterii;

    @NotNull(message = "Selectează o valoare pentru sex.")
    private Sex sex = Sex.NEPRECIZAT;

    @Positive(message = "Înălțimea trebuie să fie mai mare decât zero.")
    private Integer inaltime;

    @Positive(message = "Greutatea trebuie să fie mai mare decât zero.")
    private Double greutate;

    private String grupaSangvina;
}