package com.therapeutica.therapeutica_app;

import com.therapeutica.administrare.ComandaAdministrare;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class TherapeuticaAppApplication {

    public static void main(String[] args) {
        if (args.length > 0
                && "creare-administrator".equals(args[0])) {

            int rezultat = ComandaAdministrare.creeazaAdministrator(
                    Arrays.copyOfRange(args, 1, args.length)
            );

            System.exit(rezultat);
            return;
        }

        SpringApplication.run(TherapeuticaAppApplication.class, args);
    }
}