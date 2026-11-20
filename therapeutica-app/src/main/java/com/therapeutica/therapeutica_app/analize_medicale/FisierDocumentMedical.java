package com.therapeutica.therapeutica_app.analize_medicale;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "fisiere_documente_medicale")
@Getter
@Setter
public class FisierDocumentMedical implements Persistable<UUID> {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false, updatable = false)
    private DocumentMedical document;

    @Column(
            name = "cheie_stocare",
            nullable = false,
            length = 512,
            updatable = false
    )
    private String cheieStocare;

    @Column(name = "nume_afisat", nullable = false, length = 255)
    private String numeAfisat;

    @Column(name = "nume_original", length = 255)
    private String numeOriginal;

    @Column(name = "tip_media", nullable = false, length = 100)
    private String tipMedia;

    @Column(name = "dimensiune_octeti", nullable = false)
    private long dimensiuneOcteti;

    @Column(name = "ordine", nullable = false)
    private int ordine;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    @Column(name = "data_creare", nullable = false, updatable = false)
    private LocalDateTime dataCreare = LocalDateTime.now();

    @Transient
    private boolean isNew = true;

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    protected void markNotNew() {
        isNew = false;
    }
}