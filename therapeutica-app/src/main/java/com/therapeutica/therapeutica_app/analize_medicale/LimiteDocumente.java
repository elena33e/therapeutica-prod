package com.therapeutica.therapeutica_app.analize_medicale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record LimiteDocumente(
        @Value("${app.storage.max-file-bytes:10485760}")
        long maxOctetiFisier,

        @Value("${app.storage.max-files-per-document:10}")
        int maxFisiereDocument,

        @Value("${app.storage.max-document-bytes:52428800}")
        long maxOctetiDocument,

        @Value("${app.storage.max-image-pixels:25000000}")
        long maxPixeliImagine
) {

    public LimiteDocumente {
        if (maxOctetiFisier <= 0
                || maxOctetiFisier > Integer.MAX_VALUE - 1L
                || maxFisiereDocument <= 0
                || maxOctetiDocument <= 0
                || maxPixeliImagine <= 0) {

            throw new IllegalArgumentException(
                    "Limitele documentelor nu sunt valide."
            );
        }
    }
}