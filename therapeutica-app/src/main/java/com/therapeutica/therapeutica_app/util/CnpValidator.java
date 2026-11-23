package com.therapeutica.therapeutica_app.util;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Pattern;

public final class CnpValidator {

    private static final Pattern FORMAT =
            Pattern.compile("[1-9][0-9]{12}");

    private static final String PONDERI = "279146358279";

    private static final ZoneId ZONA =
            ZoneId.of("Europe/Bucharest");

    private CnpValidator() {
    }

    public static boolean esteValid(String cnp) {
        return esteValid(cnp, LocalDate.now(ZONA));
    }

    // Data explicită permite teste independente de ziua executării.
    static boolean esteValid(String cnp, LocalDate dataReferinta) {
        if (cnp == null || !FORMAT.matcher(cnp).matches()) {
            return false;
        }

        int primaCifra = cnp.charAt(0) - '0';
        int anScurt = Integer.parseInt(cnp.substring(1, 3));
        int luna = Integer.parseInt(cnp.substring(3, 5));
        int zi = Integer.parseInt(cnp.substring(5, 7));
        int codTeritorial = Integer.parseInt(cnp.substring(7, 9));
        int secventa = Integer.parseInt(cnp.substring(9, 12));

        if (!codTeritorialValid(codTeritorial) || secventa == 0) {
            return false;
        }

        int secol = switch (primaCifra) {
            case 1, 2 -> 1900;
            case 3, 4 -> 1800;
            case 5, 6 -> 2000;
            default -> 0;
        };

        boolean dataValida;

        if (secol != 0) {
            dataValida = dataValida(
                    secol + anScurt,
                    luna,
                    zi,
                    dataReferinta
            );
        } else {
            // Pentru 7/8/9 verificăm existența unei date compatibile.
            // Nu stabilim secolul sau data nașterii persoanei.
            dataValida =
                    dataValida(1800 + anScurt, luna, zi, dataReferinta)
                            || dataValida(1900 + anScurt, luna, zi, dataReferinta)
                            || dataValida(2000 + anScurt, luna, zi, dataReferinta);
        }

        if (!dataValida) {
            return false;
        }

        int suma = 0;

        for (int i = 0; i < 12; i++) {
            suma += (cnp.charAt(i) - '0')
                    * (PONDERI.charAt(i) - '0');
        }

        int rest = suma % 11;
        int cifraControl = rest == 10 ? 1 : rest;

        return cifraControl == cnp.charAt(12) - '0';
    }

    private static boolean codTeritorialValid(int cod) {
        // Include și codurile istorice 47/48.
        return (cod >= 1 && cod <= 48)
                || cod == 51
                || cod == 52
                || cod == 70;
    }

    private static boolean dataValida(
            int an,
            int luna,
            int zi,
            LocalDate dataReferinta) {

        try {
            LocalDate data = LocalDate.of(an, luna, zi);
            return !data.isAfter(dataReferinta);
        } catch (DateTimeException e) {
            return false;
        }
    }
}