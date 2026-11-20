package com.therapeutica.therapeutica_app.analize_medicale.stocare;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

public interface StocareDocumente {

    record FisierStocat(
            String cheieStocare,
            long dimensiuneOcteti,
            String sha256
    ) {
    }

    // Apelantul este responsabil pentru închiderea fluxului sursă.
    FisierStocat salveaza(
            UUID documentId,
            UUID fisierId,
            InputStream sursa,
            long limitaOcteti
    ) throws IOException;

    // Apelantul este responsabil pentru închiderea fluxului returnat.
    InputStream deschide(String cheieStocare) throws IOException;

    boolean sterge(String cheieStocare) throws IOException;
}