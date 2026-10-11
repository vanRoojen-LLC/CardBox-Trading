package com.cardpricer.gui.dialog;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class PasteImportDialogTest {
    @ParameterizedTest
    @CsvSource({
            "2 Terrasymbiosis (PEOE) 210s *F*, peoe, 210s, F",
            "2 Terrasymbiosis (PEOE) 210p *F*, peoe, 210p, F",
            "2 Infinite Guideline Station (PEOE) 219s *F*, peoe, 219s, F",
            "2 Infinite Guideline Station (PEOE) 219p *F*, peoe, 219p, F",
            "2 The Lost and the Damned (40K) 129 *S*, 40k, 129★, S",
            "2 The Lost and the Damned (40K) 129★ *S*, 40k, 129★, S",
            "2 Ravages of War (PIP) 882 *S*, pip, 882, S",
            "2 Example (TST) 73★ *S*, tst, 73★, S",
            "2 Example (PLST) ARB-73★ *E*, plst, arb-73★, E"
    })
    void deckImportsKeepProviderPrintingAndFinish(String input,String set,String number,String finish) {
        var cards=PasteImportDialog.parseMoxfieldLine(input);
        assertEquals(2,cards.size());
        for (var card:cards) {
            assertEquals(set,card.setCode);
            assertEquals(number,card.collectorNumber);
            assertEquals(finish,card.finish);
        }
    }
}
