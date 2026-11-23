package com.therapeutica.therapeutica_app.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class CnpValidatorTest {

    private static final LocalDate REFERINTA =
            LocalDate.of(2026, 11, 23);

    // Valori sintetice, folosite exclusiv în testele automate.
    @ParameterizedTest
    @ValueSource(strings = {
            "1900101400012",
            "2900101400014",
            "3850101400010",
            "4850101400012",
            "5000229400019",
            "6000229700014",
            "7900101400013",
            "8900101400015",
            "9900101400017",
            "1780101470016",
            "2780101480015",
            "1900101700016",
            "1900101400020",
            "1900101400081"
    })
    void acceptaStructuriValide(String cnp) {
        assertThat(CnpValidator.esteValid(cnp, REFERINTA))
                .isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            "123",
            "1234567890123",
            "19001014000122",
            "190010140001X",
            " 1900101400012",
            "1900101400012 ",
            "1900101400013",
            "0900101400010",
            "1901301400010",
            "1900431400011",
            "3000229400015",
            "1000229400011",
            "1900101000014",
            "1900101490018",
            "1900101500017",
            "1900101690017",
            "1900101400004"
    })
    void refuzaStructuriInvalide(String cnp) {
        assertThat(CnpValidator.esteValid(cnp, REFERINTA))
                .isFalse();
    }

    @Test
    void dataNasteriiNuPoateFiInViitorCandSecolulEsteCunoscut() {
        String cnp = "1900101400012";

        assertThat(CnpValidator.esteValid(
                cnp,
                LocalDate.of(1989, 12, 31)
        )).isFalse();

        assertThat(CnpValidator.esteValid(
                cnp,
                LocalDate.of(1990, 1, 1)
        )).isTrue();
    }
}