package com.therapeutica.administrare;

import com.therapeutica.therapeutica_app.admin.AdministratorInitialService;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.EnableTransactionManagement;

public final class ComandaAdministrare {

    private ComandaAdministrare() {}

    public static int creeazaAdministrator(String[] args) {
        String parola = System.getenv("THERAPEUTICA_ADMIN_PASSWORD");

        if (parola == null || parola.isBlank()) {
            System.err.println(
                    "Setează variabila THERAPEUTICA_ADMIN_PASSWORD."
            );
            return 2;
        }

        try (ConfigurableApplicationContext context =
                     deschideContext(args)) {

            var environment = context.getEnvironment();
            var serviciu = context.getBean(
                    AdministratorInitialService.class
            );

            try {
                boolean creat = serviciu.creeaza(
                        environment.getProperty("admin.email", ""),
                        environment.getProperty("admin.nume", ""),
                        environment.getProperty("admin.prenume", ""),
                        parola
                );

                System.out.println(creat
                        ? "Administratorul inițial a fost creat."
                        : "Există deja un administrator. Conturile nu au fost modificate.");

                return 0;
            } catch (IllegalStateException e) {
                System.err.println(e.getMessage());
                return 2;
            }
        } catch (RuntimeException e) {
            System.err.println(
                    "Comanda a eșuat. Verifică profilul și conexiunea la baza de date."
            );
            return 1;
        }
    }

    static ConfigurableApplicationContext deschideContext(
            String... args) {

        SpringApplication application =
                new SpringApplication(Configuratie.class);

        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);

        return application.run(args);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(AdministratorInitialService.class)
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            ValidationAutoConfiguration.class
    })
    public static class Configuratie {

        @Bean
        PasswordEncoder passwordEncoder() {
            return new BCryptPasswordEncoder();
        }
    }
}