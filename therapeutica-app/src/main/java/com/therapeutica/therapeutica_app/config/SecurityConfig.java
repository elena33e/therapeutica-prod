package com.therapeutica.therapeutica_app.config;

import com.therapeutica.therapeutica_app.security.CustomUserDetailsService;
import com.therapeutica.therapeutica_app.security.LoginSuccessHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Autowired
    private LoginSuccessHandler loginSuccessHandler;

    @Autowired
    private CustomUserDetailsService customUserDetailsService;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(HttpSecurity http)
            throws Exception {

        AuthenticationManagerBuilder auth =
                http.getSharedObject(AuthenticationManagerBuilder.class);

        auth.userDetailsService(customUserDetailsService)
                .passwordEncoder(passwordEncoder());

        return auth.build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
                .csrf(Customizer.withDefaults())
                .headers(headers -> headers
                        .frameOptions(
                                HeadersConfigurer.FrameOptionsConfig::sameOrigin
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        // Singurele rute API folosite pentru notificări.
                        // Proprietatea datelor se verifică în serviciu.
                        .requestMatchers(
                                HttpMethod.GET,
                                "/api/notificari/necitite-count/*"
                        ).hasAnyRole("MEDIC", "PACIENT")

                        .requestMatchers(
                                HttpMethod.POST,
                                "/api/notificari/acceseaza/*"
                        ).hasAnyRole("MEDIC", "PACIENT")

                        // Celelalte API-uri sunt retrase sau neautorizate.
                        .requestMatchers("/api", "/api/**").denyAll()

                        .requestMatchers(
                                "/chestionare/raport",
                                "/chestionare/raport/**",
                                "/chestionare/rezultate",
                                "/chestionare/rezultate/**",
                                "/raspunsuri-chestionare",
                                "/raspunsuri-chestionare/**",
                                "/medic/*/pacienti-neevaluati",
                                "/medic/*/pacient/*/chestionare",
                                "/medic/*/chestionar/*/rezultate"
                        ).denyAll()

                        .requestMatchers(
                                "/debug-env",
                                "/debug-env/**"
                        ).denyAll()

                        .requestMatchers(
                                "/admin",
                                "/admin/dashboard/**"
                        ).hasRole("ADMIN")

                        // Administrarea chestionarelor va fi tratată separat.
                        .requestMatchers("/admin/**").denyAll()

                        .requestMatchers(
                                "/generare-cod",
                                "/generare-cod/**"
                        ).hasAnyRole("ADMIN", "MEDIC")

                        .requestMatchers(
                                "/",
                                "/login",
                                "/inregistrare",
                                "/css/**",
                                "/js/**",
                                "/fonts/**",
                                "/images/**",
                                "/error",
                                "/resetare-parola/**"
                        ).permitAll()

                        .requestMatchers("/medic/**").hasRole("MEDIC")

                        .requestMatchers(
                                "/chestionare/rezultate/**",
                                "/chestionare/raport/**",
                                "/chestionare/medic/**"
                        ).hasRole("MEDIC")

                        .requestMatchers("/diagnostic/**").hasRole("MEDIC")

                        .requestMatchers("/pacient/**").hasRole("PACIENT")

                        .requestMatchers(
                                "/chestionare/completare/**",
                                "/raspunsuri/**"
                        ).hasRole("PACIENT")

                        .requestMatchers(
                                "/analize/**",
                                "/documente/**",
                                "/chestionare/**",
                                "/raspunsuri/**",
                                "/raspunsuri-chestionare/**"
                        ).hasAnyRole("MEDIC", "PACIENT")

                        .anyRequest().hasAnyRole("MEDIC", "PACIENT")
                )
                .formLogin(login -> login
                        .loginPage("/login")
                        .loginProcessingUrl("/process-login")
                        .successHandler(loginSuccessHandler)
                        .failureUrl("/login?error=true")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout-web")
                        .logoutSuccessUrl("/login?logout=true")
                        .invalidateHttpSession(true)
                        .clearAuthentication(true)
                        .deleteCookies("JSESSIONID")
                        .permitAll()
                );

        return http.build();
    }
}