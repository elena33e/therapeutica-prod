# Therapeutica

Aplicație pentru completarea chestionarelor de către pacienți, consultarea rezultatelor de către medicul alocat și gestionarea documentelor medicale originale.

Versiunea de producție este în pregătire. Publicarea codului nu reprezintă lansarea aplicației pentru utilizatori reali.

## Funcționalități

- Completarea chestionarelor atribuite pacientului.
- Validarea răspunsurilor obligatorii și a scorurilor între 0 și 3.
- Salvarea atomică a răspunsurilor, statusului și scorului.
- Consultarea rapoartelor de către medicul autorizat pentru pacient.
- Încărcarea și consultarea documentelor originale PDF sau imagini.
- Păstrarea diagnosticelor introduse manual de medic.

OCR, maparea la ontologii, profilul fenotipic și inferența automată sunt excluse din această versiune.

## Tehnologii

Java 17, Spring Boot 3.5.7, PostgreSQL 18.1, Maven Wrapper, Spring Security, Thymeleaf, HTMX și Tailwind CSS.

Testele de integrare folosesc Testcontainers 1.21.4 și PostgreSQL temporar.

## Structura proiectului

- `therapeutica-app/pom.xml`: proiectul Maven.
- `therapeutica-app/src/main`: codul aplicației și resursele interfeței.
- `therapeutica-app/src/test`: testele și configurațiile lor.
- `.github/workflows/ci.yml`: verificările automate pentru `main` și PR-urile către această ramură.

## Testare

Sunt necesare Java 17 și un Docker Engine accesibil din mediul în care rulează testele. Pe Windows, testele au fost verificate din Ubuntu WSL2.

Din rădăcina repository-ului:

```bash
cd therapeutica-app
bash mvnw -Dskip.installnodenpm=true -Dskip.npm=true test
```

Testele de integrare creează baze PostgreSQL temporare. Configurațiile lor sunt versionate în `src/test/resources`.

## Build complet

Din directorul care conține `pom.xml`:

```bash
bash mvnw --batch-mode --no-transfer-progress verify
```

Buildul complet include pregătirea resurselor interfeței și testele. Aceeași comandă este utilizată în CI.

## Configurare locală

Pornirea aplicației necesită configurarea conexiunii PostgreSQL, a serviciului de email și a directorului pentru documente prin `app.storage.documents-dir`.

Fișierele locale `application.yml` și `application-production.yml` sunt ignorate în Git. Parolele, documentele medicale și backupurile se păstrează în afara repository-ului.

Configurația destinată producției trebuie să folosească `spring.jpa.hibernate.ddl-auto: validate`.

## Migrări

Migrările V1, V2 și V3 se află în `therapeutica-app/src/main/resources/db/migration`.

Aplicația folosește `spring.jpa.hibernate.ddl-auto: validate`. Dependențele Flyway ale aplicației au `scope=test`; pornirea serverului web nu execută migrări.

Administrarea explicită folosește profilul Maven `migrari`, configurat prin:

- `THERAPEUTICA_MIGRARI_DB_URL`
- `THERAPEUTICA_MIGRARI_DB_USER`
- `THERAPEUTICA_MIGRARI_DB_PASSWORD`

Din directorul modulului Maven, starea se verifică folosind `.\mvnw.cmd -Pmigrari flyway:info` în PowerShell sau `bash mvnw -Pmigrari flyway:info` în Linux.

Aplicarea migrărilor se face explicit prin `flyway:migrate`, după verificarea destinației și a backupului. Înregistrarea unui baseline necesită verificarea prealabilă a structurii și datelor. `baseline-on-migrate` este dezactivat, iar operația `clean` este blocată.

Scripturile operaționale sunt în `scripts/db`:

- `pregatire-adoptare-v3.sql`: pregătește o schemă istorică V1 cu V3 aplicată manual și fără istoric Flyway. Include V2 originală și verifică păstrarea datelor într-o singură tranzacție.
- `configurare-roluri-v3.sql`: configurează proprietarii și drepturile unei baze verificate la versiunea 3.

Ambele scripturi primesc parametrul psql `baza_asteptata` și refuză o bază diferită.

Contul `therapeutica_migrari_user` deține schema. Contul `therapeutica_aplicatie_user` are drepturile necesare datelor aplicației, fără drepturi de modificare a structurii sau acces la istoricul Flyway. Drepturile pentru obiectele viitoare se acordă explicit în migrările respective.

Testele operaționale rulează exclusiv pe PostgreSQL temporar și verifică adoptarea, păstrarea datelor, rollbackul, refuzul bazei greșite și separarea drepturilor.

## Proveniența codului

Acest repository pornește din ramura `versiune-productie` a proiectului [elena33e/therapeutica-app](https://github.com/elena33e/therapeutica-app), de la commitul:

`5ad9106271f4c29ccc5c4aa329f5c4da33d76704`

[Consultă commitul sursă](https://github.com/elena33e/therapeutica-app/commit/5ad9106271f4c29ccc5c4aa329f5c4da33d76704).

Repository-ul de producție începe cu un istoric nou. Istoricul anterior și versiunea disertației, identificată prin tagul [disertatie-v1](https://github.com/elena33e/therapeutica-app/tree/disertatie-v1), sunt păstrate în repository-ul original.

## Administrarea conturilor

Administratorul invită medici, iar medicii invită pacienți. Activarea creează contul și profilul corespunzător într-o singură tranzacție.

Primul administrator se creează explicit prin comanda `creare-administrator`, executată din JAR-ul aplicației. Comanda primește proprietățile `admin.email`, `admin.nume` și `admin.prenume`. Parola se transmite prin variabila de mediu `THERAPEUTICA_ADMIN_PASSWORD`.

Comanda folosește configurația conexiunii PostgreSQL a profilului selectat. Nu pornește serverul web și nu execută migrări. Dacă există deja un administrator, nu creează altul și nu modifică parolele sau rolurile conturilor existente.

Comenzile operator `creare-administrator` și `migrare-documente` blochează tabele și necesită o configurație de conexiune dedicată, cu drepturile contului de migrări. Serverul web folosește contul limitat al aplicației.