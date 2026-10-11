package com.cardpricer.util;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class CardCodeParserTest {
    @ParameterizedTest
    @CsvSource({
            "PEOE 210sf, peoe, 210s, F",
            "PEOE 210pf, peoe, 210p, F",
            "peoe 219pf, peoe, 219p, F",
            "peoe 219sf, peoe, 219s, F",
            "peoe210SF, peoe, 210s, F",
            "PWAR 220s★f, pwar, 220s★, F",
            "PEOE 210s F, peoe, 210s, F",
            "PEOE 210s, peoe, 210s, ''",
            "PWAR 220s, pwar, 220s, ''",
            "PEOE 210 s, peoe, 210, S",
            "PEOE 210ss, peoe, 210s, S",
            "PIP 882s, pip, 882, S",
            "PIP 882 S, pip, 882, S",
            "PIP 882f, pip, 882, F",
            "TST 73★s, tst, 73★, S",
            "TST 73★f, tst, 73★, F",
            "TST 73★ e, tst, 73★, E",
            "TST 73★, tst, 73★, ''",
            "PLST ARB 73★e, plst, ARB-73★, E",
            "PLST ARB-73s, plst, ARB-73, S",
            "CHR143Bf, chr, 143b, F",
            "CHR143B, chr, 143b, ''",
            "TDM 3e, tdm, 3, E",
            "40k 129s, 40k, 129★, S",
            "40K 129 S, 40k, 129★, S",
            "40K 129★s, 40k, 129★, S",
            "40K 129★ F, 40k, 129★, F",
            "40K 129, 40k, 129, ''",
            "C17 149, c17, 149, ''"
    })
    void finishIsSeparateFromExactCollectorNumberAndSurvivesEnterFormatting(
            String input, String set, String number, String finish) {
        var parsed=CardCodeParser.parse(input);
        assertNotNull(parsed,input);
        assertEquals(set,parsed.setCode);
        assertEquals(number,parsed.collectorNumber);
        assertEquals(finish,parsed.finish);
        // Trade entry formats the text on Enter, then parses it again to look up the card.
        String display=CardCodeParser.format(parsed);
        var reparsed=CardCodeParser.parse(display);
        assertNotNull(reparsed,display);
        assertEquals(set,reparsed.setCode,display);
        assertEquals(number,reparsed.collectorNumber,display);
        assertEquals(finish,reparsed.finish,display);
        assertEquals(display,CardCodeParser.format(reparsed));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Black Lotus", "Lightning Bolt", "PEOE", "PEOE nope", "TDM ARB 3", ""})
    void namesAndInvalidCodesAreNotParsed(String input) {
        assertNull(CardCodeParser.parse(input));
    }
}
