\set ON_ERROR_STOP on

BEGIN;

SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';
SET LOCAL row_security = off;

SELECT set_config(
    'therapeutica.adoptare_baza',
    :'baza_asteptata',
    true
);

DO $verificare$
DECLARE
    tabele_de_blocat text;
BEGIN
    IF current_database() IS DISTINCT FROM
       current_setting('therapeutica.adoptare_baza') THEN
        RAISE EXCEPTION
            'Baza conectată este %, dar baza așteptată este %.',
            current_database(),
            current_setting('therapeutica.adoptare_baza');
    END IF;

    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        RAISE EXCEPTION
            'Baza are deja istoric Flyway. Procedura de adoptare nu se repetă.';
    END IF;

    IF to_regclass('public.istoric_diagnoze') IS NULL
       OR to_regclass('public.documente_medicale') IS NULL
       OR to_regclass('public.fisiere_documente_medicale') IS NULL THEN
        RAISE EXCEPTION
            'Nu este prezentă schema istorică împreună cu evidența V3.';
    END IF;

    SELECT string_agg(
        format('%I.%I', table_schema, table_name),
        ', ' ORDER BY table_name
    )
    INTO tabele_de_blocat
    FROM information_schema.tables
    WHERE table_schema = 'public'
      AND table_type = 'BASE TABLE';

    EXECUTE format(
        'LOCK TABLE %s IN ACCESS EXCLUSIVE MODE',
        tabele_de_blocat
    );

    IF EXISTS (
        SELECT 1
        FROM public.documente_medicale d
        WHERE d.cale_fisier_stocare IS DISTINCT FROM d.id::text
           OR NOT EXISTS (
               SELECT 1
               FROM public.fisiere_documente_medicale f
               WHERE f.document_id = d.id
           )
    ) THEN
        RAISE EXCEPTION
            'Există documente fără evidență de fișiere sau fără identificator relativ.';
    END IF;
END
$verificare$;

CREATE TEMP TABLE date_inainte_adoptare (
    tabela text PRIMARY KEY,
    continut jsonb NOT NULL
) ON COMMIT DROP;

CREATE FUNCTION pg_temp.date_pastrate_adoptare()
RETURNS TABLE(tabela text, continut jsonb)
LANGUAGE plpgsql
AS $functie$
DECLARE
    tabela_curenta record;
    expresie text;
BEGIN
    FOR tabela_curenta IN
        SELECT t.table_name
        FROM information_schema.tables t
        WHERE t.table_schema = 'public'
          AND t.table_type = 'BASE TABLE'
          AND t.table_name <> 'istoric_diagnoze'
        ORDER BY t.table_name
    LOOP
        IF tabela_curenta.table_name = 'documente_medicale' THEN
            expresie := $expresie$
                (
                    to_jsonb(r) - ARRAY[
                        'date_brute',
                        'date_validate',
                        'date_standardizate_loinc',
                        'date_interpretate_fhir',
                        'candidati_loinc',
                        'date_fhir_standard'
                    ]::text[]
                )
                || jsonb_build_object(
                    'status',
                    CASE
                        WHEN r.status = 'STERS_DE_PACIENT'
                            THEN 'STERS_DE_PACIENT'
                        ELSE 'INCARCAT'
                    END
                )
            $expresie$;

        ELSIF tabela_curenta.table_name = 'intrebari' THEN
            expresie := $expresie$
                to_jsonb(r) - ARRAY[
                    'hpo_code',
                    'hpo_term',
                    'hpo_trigger_code',
                    'hpo_trigger_term'
                ]::text[]
            $expresie$;

        ELSE
            expresie := 'to_jsonb(r)';
        END IF;

        RETURN QUERY EXECUTE format(
            $interogare$
                SELECT
                    %L::text,
                    COALESCE(
                        jsonb_agg(
                            randuri.continut
                            ORDER BY randuri.continut::text COLLATE "C"
                        ),
                        '[]'::jsonb
                    )
                FROM (
                    SELECT %s AS continut
                    FROM public.%I r
                ) randuri
            $interogare$,
            tabela_curenta.table_name,
            expresie,
            tabela_curenta.table_name
        );
    END LOOP;
END
$functie$;

INSERT INTO pg_temp.date_inainte_adoptare (tabela, continut)
SELECT tabela, continut
FROM pg_temp.date_pastrate_adoptare();

-- Folosim fișierul V2 original, fără să duplicăm sau să modificăm migrarea.
\ir ../../therapeutica-app/src/main/resources/db/migration/V2__eliminare_functionalitati_disertatie.sql

DO $verificare$
DECLARE
    tabele_modificate text;
BEGIN
    SELECT string_agg(
        COALESCE(inainte.tabela, dupa.tabela),
        ', ' ORDER BY COALESCE(inainte.tabela, dupa.tabela)
    )
    INTO tabele_modificate
    FROM pg_temp.date_inainte_adoptare inainte
    FULL JOIN pg_temp.date_pastrate_adoptare() dupa
        USING (tabela)
    WHERE inainte.continut IS DISTINCT FROM dupa.continut;

    IF tabele_modificate IS NOT NULL THEN
        RAISE EXCEPTION
            'Date modificate în afara transformărilor permise: %.',
            tabele_modificate;
    END IF;
END
$verificare$;

SELECT
    tabela,
    jsonb_array_length(continut) AS inregistrari_pastrate
FROM pg_temp.date_inainte_adoptare
ORDER BY tabela;

SELECT status, count(*) AS documente
FROM public.documente_medicale
GROUP BY status
ORDER BY status;

DROP FUNCTION pg_temp.date_pastrate_adoptare();

COMMIT;

\echo Pregatirea schemei pentru adoptare a fost finalizata.