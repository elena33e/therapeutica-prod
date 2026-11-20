package com.therapeutica.therapeutica_app.analize_medicale;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FisierDocumentMedicalRepository
        extends JpaRepository<FisierDocumentMedical, UUID> {

    List<FisierDocumentMedical> findByDocument_IdOrderByOrdineAsc(
            UUID documentId
    );
}