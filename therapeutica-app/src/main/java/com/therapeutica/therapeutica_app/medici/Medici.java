package com.therapeutica.therapeutica_app.medici;

import com.therapeutica.therapeutica_app.pacienti.Pacienti;
import com.therapeutica.therapeutica_app.utilizatori.Utilizatori;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "medici")
@Getter
@Setter
public class Medici {

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private Utilizatori user;

    @Column(nullable = false, columnDefinition = "text")
    private String specializare;

    @Column(name = "telefon", length = 20)
    private String telefon;

    @OneToMany(mappedBy = "medic", fetch = FetchType.LAZY)
    private Set<Pacienti> pacienti;
}