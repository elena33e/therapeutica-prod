\set ON_ERROR_STOP on

BEGIN;

SET LOCAL lock_timeout = '5s';

SELECT set_config(
    'therapeutica.baza_asteptata',
    :'baza_asteptata',
    true
);

DO $configurare$
DECLARE
    obiect record;
BEGIN
    IF current_database() IS DISTINCT FROM
       current_setting('therapeutica.baza_asteptata') THEN
        RAISE EXCEPTION 'Conectare la baza greșită: %.',
            current_database();
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname = current_user
          AND rolsuper
    ) THEN
        RAISE EXCEPTION
            'Configurarea trebuie executată de administratorul PostgreSQL.';
    END IF;

    IF to_regclass('public.flyway_schema_history') IS NULL THEN
        RAISE EXCEPTION 'Lipsește istoricul Flyway.';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM public.flyway_schema_history
        WHERE version = '3'
          AND success
    ) OR EXISTS (
        SELECT 1
        FROM public.flyway_schema_history
        WHERE NOT success
           OR (
               version IS NOT NULL
               AND version NOT IN ('1', '2', '3')
           )
    ) THEN
        RAISE EXCEPTION
            'Procedura necesită schema verificată la versiunea 3.';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_roles
        WHERE rolname = 'therapeutica_migrari_user'
    ) THEN
        CREATE ROLE therapeutica_migrari_user
            LOGIN NOINHERIT
            NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOREPLICATION NOBYPASSRLS;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_roles
        WHERE rolname = 'therapeutica_aplicatie_user'
    ) THEN
        CREATE ROLE therapeutica_aplicatie_user
            LOGIN NOINHERIT
            NOSUPERUSER NOCREATEDB NOCREATEROLE
            NOREPLICATION NOBYPASSRLS;
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_roles
        WHERE rolname IN (
            'therapeutica_migrari_user',
            'therapeutica_aplicatie_user'
        )
          AND (
              rolsuper OR rolcreatedb OR rolcreaterole
              OR rolreplication OR rolbypassrls
              OR rolinherit OR NOT rolcanlogin
          )
    ) OR EXISTS (
        SELECT 1
        FROM pg_auth_members m
        JOIN pg_roles r ON r.oid = m.member
        WHERE r.rolname IN (
            'therapeutica_migrari_user',
            'therapeutica_aplicatie_user'
        )
    ) THEN
        RAISE EXCEPTION
            'Rolurile existente au atribute sau apartenențe incompatibile.';
    END IF;

    EXECUTE format(
        'ALTER DATABASE %I OWNER TO therapeutica_migrari_user',
        current_database()
    );

    ALTER SCHEMA public OWNER TO therapeutica_migrari_user;

    FOR obiect IN
        SELECT tablename
        FROM pg_tables
        WHERE schemaname = 'public'
        ORDER BY tablename
    LOOP
        EXECUTE format(
            'ALTER TABLE public.%I OWNER TO therapeutica_migrari_user',
            obiect.tablename
        );
    END LOOP;

    FOR obiect IN
        SELECT sequence_name
        FROM information_schema.sequences
        WHERE sequence_schema = 'public'
    LOOP
        EXECUTE format(
            'ALTER SEQUENCE public.%I OWNER TO therapeutica_migrari_user',
            obiect.sequence_name
        );
    END LOOP;

    ALTER TYPE public.role_type OWNER TO therapeutica_migrari_user;
    ALTER TYPE public.status_cod OWNER TO therapeutica_migrari_user;

    EXECUTE format(
        'REVOKE ALL PRIVILEGES ON DATABASE %I
         FROM PUBLIC, therapeutica_productie_user,
              therapeutica_aplicatie_user',
        current_database()
    );

    EXECUTE format(
        'GRANT CONNECT ON DATABASE %I
         TO therapeutica_migrari_user, therapeutica_aplicatie_user',
        current_database()
    );
END
$configurare$;

REVOKE ALL PRIVILEGES ON SCHEMA public
FROM PUBLIC, therapeutica_productie_user, therapeutica_aplicatie_user;

GRANT USAGE ON SCHEMA public TO therapeutica_aplicatie_user;

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public
FROM PUBLIC, therapeutica_productie_user, therapeutica_aplicatie_user;

REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public
FROM PUBLIC, therapeutica_productie_user, therapeutica_aplicatie_user;

REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public
FROM PUBLIC, therapeutica_productie_user, therapeutica_aplicatie_user;

REVOKE ALL PRIVILEGES ON TYPE public.role_type, public.status_cod
FROM PUBLIC, therapeutica_productie_user, therapeutica_aplicatie_user;

GRANT USAGE ON TYPE public.role_type, public.status_cod
TO therapeutica_aplicatie_user;

-- Catalogul chestionarelor: numai citire.
GRANT SELECT ON TABLE
    public.chestionare,
    public.categorii_chestionare,
    public.intrebari
TO therapeutica_aplicatie_user;

-- Auditul și evidența fișierelor: citire și adăugare.
GRANT SELECT, INSERT ON TABLE
    public.audit_log,
    public.fisiere_documente_medicale
TO therapeutica_aplicatie_user;

-- Documentele se retrag prin status, păstrând înregistrările.
GRANT SELECT, INSERT, UPDATE ON TABLE
    public.documente_medicale,
    public.notificari
TO therapeutica_aplicatie_user;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    public.cod_inregistrare,
    public.diagnostic_analize_ref,
    public.diagnostic_chestionare_ref,
    public.diagnostic_medic,
    public.medici,
    public.pacienti,
    public.raspunsuri_chestionare,
    public.raspunsuri_intrebari,
    public.rezultate_scoring,
    public.tokenuri_resetare,
    public.utilizatori
TO therapeutica_aplicatie_user;

-- Obiectele viitoare primesc drepturi explicit în migrările lor.
ALTER DEFAULT PRIVILEGES FOR ROLE therapeutica_migrari_user
    REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

ALTER DEFAULT PRIVILEGES FOR ROLE therapeutica_migrari_user
    REVOKE USAGE ON TYPES FROM PUBLIC;

DO $verificare$
BEGIN
    IF has_database_privilege(
        'therapeutica_aplicatie_user', current_database(), 'CREATE'
    ) OR has_database_privilege(
        'therapeutica_aplicatie_user', current_database(), 'TEMP'
    ) OR has_schema_privilege(
        'therapeutica_aplicatie_user', 'public', 'CREATE'
    ) OR has_table_privilege(
        'therapeutica_aplicatie_user',
        'public.flyway_schema_history',
        'SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER,MAINTAIN'
    ) THEN
        RAISE EXCEPTION
            'Contul aplicației are drepturi administrative nepermise.';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_tables
        WHERE schemaname = 'public'
          AND tableowner <> 'therapeutica_migrari_user'
    ) THEN
        RAISE EXCEPTION
            'Există tabele care nu aparțin contului de migrări.';
    END IF;
END
$verificare$;

COMMIT;

SELECT
    current_database() AS baza,
    pg_get_userbyid(d.datdba) AS proprietar,
    has_database_privilege(
        'therapeutica_aplicatie_user', d.oid, 'CONNECT'
    ) AS aplicatie_conectare,
    has_database_privilege(
        'therapeutica_aplicatie_user', d.oid, 'CREATE'
    ) AS aplicatie_creare_scheme,
    has_database_privilege(
        'therapeutica_aplicatie_user', d.oid, 'TEMP'
    ) AS aplicatie_tabele_temporare,
    has_schema_privilege(
        'therapeutica_aplicatie_user', 'public', 'CREATE'
    ) AS aplicatie_creare_in_public,
    has_table_privilege(
        'therapeutica_aplicatie_user',
        'public.flyway_schema_history',
        'SELECT'
    ) AS aplicatie_citire_istoric_flyway
FROM pg_database d
WHERE d.datname = current_database();