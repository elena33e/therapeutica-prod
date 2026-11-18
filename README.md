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

Migrările V1 și V2 se află în `src/main/resources/db/migration`.

Flyway este momentan disponibil doar în teste. Aplicarea migrărilor pe baza existentă este o etapă separată, care necesită backup și verificarea procedurii de recuperare.

## Proveniența codului

Acest repository pornește din ramura `versiune-productie` a proiectului [elena33e/therapeutica-app](https://github.com/elena33e/therapeutica-app), de la commitul:

`5ad9106271f4c29ccc5c4aa329f5c4da33d76704`

[Consultă commitul sursă](https://github.com/elena33e/therapeutica-app/commit/5ad9106271f4c29ccc5c4aa329f5c4da33d76704).

Repository-ul de producție începe cu un istoric nou. Istoricul anterior și versiunea disertației, identificată prin tagul [disertatie-v1](https://github.com/elena33e/therapeutica-app/tree/disertatie-v1), sunt păstrate în repository-ul original.