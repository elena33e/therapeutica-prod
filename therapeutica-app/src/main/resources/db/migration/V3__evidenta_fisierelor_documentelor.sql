CREATE TABLE public.fisiere_documente_medicale (
    id uuid PRIMARY KEY,

    document_id uuid NOT NULL,

    cheie_stocare varchar(512) NOT NULL,

    nume_afisat varchar(255) NOT NULL,

    -- Poate lipsi pentru fișiere istorice al căror nume original
    -- nu mai poate fi recuperat cu certitudine.
    nume_original varchar(255),

    tip_media varchar(100) NOT NULL,

    dimensiune_octeti bigint NOT NULL,

    ordine integer NOT NULL,

    sha256 varchar(64) NOT NULL,

    data_creare timestamp(6) without time zone
        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_fisier_document
        FOREIGN KEY (document_id)
        REFERENCES public.documente_medicale(id)
        ON DELETE RESTRICT,

    CONSTRAINT uq_fisiere_document_cheie
        UNIQUE (cheie_stocare),

    CONSTRAINT uq_fisiere_document_ordine
        UNIQUE (document_id, ordine),

    CONSTRAINT ck_fisier_cheie_relativa
        CHECK (
            cheie_stocare ~ '^[A-Za-z0-9_-]([A-Za-z0-9_.-]*[A-Za-z0-9_-])?(/[A-Za-z0-9_-]([A-Za-z0-9_.-]*[A-Za-z0-9_-])?)*$'
        ),

    CONSTRAINT ck_fisier_nume_afisat
        CHECK (
            btrim(nume_afisat) <> ''
            AND nume_afisat !~ '[[:cntrl:]]'
        ),

    CONSTRAINT ck_fisier_nume_original
        CHECK (
            nume_original IS NULL
            OR (
                btrim(nume_original) <> ''
                AND nume_original !~ '[[:cntrl:]]'
            )
        ),

    CONSTRAINT ck_fisier_tip_media
        CHECK (
            tip_media IN (
                'application/pdf',
                'image/jpeg',
                'image/png',
                'image/gif',
                'image/webp'
            )
        ),

    CONSTRAINT ck_fisier_dimensiune
        CHECK (dimensiune_octeti > 0),

    CONSTRAINT ck_fisier_ordine
        CHECK (ordine > 0),

    CONSTRAINT ck_fisier_sha256
        CHECK (sha256 ~ '^[0-9a-f]{64}$')
);