package com.therapeutica.therapeutica_app.security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.User;

import java.util.Collection;
import java.util.UUID;

public class UtilizatorAutentificat extends User {

    private static final long serialVersionUID = 1L;

    private final UUID userId;

    public UtilizatorAutentificat(
            UUID userId,
            String username,
            String password,
            Collection<? extends GrantedAuthority> authorities) {

        super(username, password, authorities);
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }
}