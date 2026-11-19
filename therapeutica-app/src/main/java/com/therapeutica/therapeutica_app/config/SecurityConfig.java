package com.therapeutica.therapeutica_app.config;

import com.therapeutica.therapeutica_app.security.LoginSuccessHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.HeadersConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import com.therapeutica.therapeutica_app.security.CustomUserDetailsService;


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
    public AuthenticationManager authenticationManager(HttpSecurity http) throws Exception {
        AuthenticationManagerBuilder auth = http.getSharedObject(AuthenticationManagerBuilder.class);
        auth.userDetailsService(customUserDetailsService).passwordEncoder(passwordEncoder());
        return auth.build();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http)
            throws Exception {

        http
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .headers(headers -> headers
                        .frameOptions(
                                HeadersConfigurer.FrameOptionsConfig::sameOrigin
                        )
                )
                .authorizeHttpRequests(auth -> auth
                        // API-uri de conturi retrase.
                        .requestMatchers(
                                "/api/utilizatoris",
                                "/api/utilizatoris/**",
                                "/api/medicis",
                                "/api/medicis/**",
                                "/api/coduri-inregistrare",
                                "/api/coduri-inregistrare/**"
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
                        // API-ul vechi pentru răspunsuri este retras.
                        .requestMatchers(
                                "/api/raspunsuri-chestionare",
                                "/api/raspunsuri-chestionare/**"
                        ).denyAll()

                        // Rutele de depanare sunt retrase.
                        .requestMatchers(
                                "/api/test",
                                "/api/test/**",
                                "/debug-env",
                                "/debug-env/**"
                        ).denyAll()

                        .requestMatchers(
                                "/",
                                "/login",
                                "/inregistrare",
                                "/css/**",
                                "/js/**",
                                "/images/**",
                                "/error",
                                "/resetare-parola/**"
                        ).permitAll()

                        .requestMatchers("/medic/**").hasRole("MEDIC")
                        .requestMatchers(
                                "/chestionare/rezultate/**",
                                "/chestionare/raport/**"
                        ).hasRole("MEDIC")
                        .requestMatchers(
                                "/chestionare/medic/**"
                        ).hasRole("MEDIC")
                        .requestMatchers("/diagnostic/**").hasRole("MEDIC")

                        .requestMatchers("/pacient/**").hasRole("PACIENT")
                        .requestMatchers(
                                "/chestionare/completare/**",
                                "/raspunsuri/**"
                        ).hasRole("PACIENT")

                        // Relația cu pacientul se verifică în servicii.
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