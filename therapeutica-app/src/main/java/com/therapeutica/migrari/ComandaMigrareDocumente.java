package com.therapeutica.migrari;

import com.therapeutica.therapeutica_app.analize_medicale.LimiteDocumente;
import com.therapeutica.therapeutica_app.analize_medicale.ValidareFisiereDocumenteService;
import com.therapeutica.therapeutica_app.analize_medicale.stocare.StocareLocalaDocumente;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.io.IOException;
import java.util.Arrays;

public final class ComandaMigrareDocumente {

    private ComandaMigrareDocumente() {
    }

    public static int executa(String[] args) {
        boolean aplica = Arrays.asList(args)
                .contains("--migrare.aplica=true");

        SpringApplication application =
                new SpringApplication(Configuratie.class);

        application.setWebApplicationType(WebApplicationType.NONE);
        application.setBannerMode(Banner.Mode.OFF);
        application.setLogStartupInfo(false);
        application.setRegisterShutdownHook(false);

        try (ConfigurableApplicationContext context =
                     application.run(args)) {

            String bazaAsteptata = context.getEnvironment()
                    .getRequiredProperty("migrare.baza-asteptata");

            var raport = context.getBean(MigrareDocumenteService.class)
                    .migreaza(bazaAsteptata, aplica);

            System.out.println(aplica
                    ? "APLICARE FINALIZATĂ."
                    : "VERIFICARE FINALIZATĂ. Nu au fost aplicate modificări.");

            System.out.printf(
                    "Documente: %d%nFișiere: %d%n"
                            + "Deja migrate: %d documente, %d fișiere%n",
                    raport.documenteNoi(),
                    raport.fisiereNoi(),
                    raport.documenteExistente(),
                    raport.fisiereExistente()
            );

            return 0;
        } catch (IOException | RuntimeException eroare) {
            System.err.println(
                    "Comanda de migrare a eșuat: " + eroare.getMessage()
            );
            return 1;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({
            MigrareDocumenteService.class,
            LimiteDocumente.class,
            ValidareFisiereDocumenteService.class,
            StocareLocalaDocumente.class
    })
    @ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class
    })
    public static class Configuratie {
    }
}