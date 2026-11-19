package com.therapeutica.therapeutica_app.notificari;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificareRepository extends JpaRepository<Notificare, Long> {

    List<Notificare> findByUtilizatorDestinatar_IdOrderByDataCreareDesc(
            UUID utilizatorDestinatarId
    );

    long countByUtilizatorDestinatar_IdAndCititaFalse(
            UUID utilizatorDestinatarId
    );

    Optional<Notificare> findByIdAndUtilizatorDestinatar_Id(
            Long id,
            UUID utilizatorDestinatarId
    );
}