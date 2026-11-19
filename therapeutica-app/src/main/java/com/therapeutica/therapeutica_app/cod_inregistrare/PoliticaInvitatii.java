package com.therapeutica.therapeutica_app.cod_inregistrare;

import com.therapeutica.therapeutica_app.utilizatori.RoleType;

public final class PoliticaInvitatii {

    private PoliticaInvitatii() {}

    public static RoleType destinatarPermis(RoleType autor) {
        if (autor == RoleType.ADMIN) {
            return RoleType.MEDIC;
        }
        if (autor == RoleType.MEDIC) {
            return RoleType.PACIENT;
        }
        return null;
    }

    public static boolean permite(RoleType autor, RoleType destinatar) {
        return destinatar != null
                && destinatar == destinatarPermis(autor);
    }
}