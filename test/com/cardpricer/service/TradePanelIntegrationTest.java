package com.cardpricer.service;
import com.cardpricer.gui.panel.TradePanel;
import com.cardpricer.gui.panel.trade.TradeTableModel;
import com.cardpricer.model.Card;
import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class TradePanelIntegrationTest {
    @Test void switchingTradesParksEditsAndReopeningResumesThemWithoutDiscardingAnotherDraft() throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            var panel=new TradePanel();var first=TradeRepositoryTest.draft("TST");var second=TradeRepositoryTest.draft("PLST");
            try {
                assertTrue(panel.editSavedTrade(first));
                var field=TradePanel.class.getDeclaredField("tableModel");field.setAccessible(true);var model=(TradeTableModel)field.get(panel);
                model.setValueAt(7,0,4);
                assertTrue(panel.editSavedTrade(second));
                assertEquals(7,TradeSessionService.find(first.id()).draft().lines().getFirst().quantity());
                model.setValueAt(9,0,4);
                assertTrue(panel.editSavedTrade(first));assertEquals(7,model.getValueAt(0,4));
                var clear=TradePanel.class.getDeclaredMethod("clearTradeState");clear.setAccessible(true);clear.invoke(panel);
                assertNull(TradeSessionService.find(first.id()));
                assertEquals(9,TradeSessionService.find(second.id()).draft().lines().getFirst().quantity());
                assertEquals(second.revision(),TradeSessionService.find(second.id()).editingRevision());
            } catch(Exception failure) { throw new IllegalStateException(failure); }
            finally { panel.disposeResources();TradeSessionService.discard(first.id());TradeSessionService.discard(second.id()); }
        });
    }
    @Test void highlightedAndCheckedTradeRowsDeleteOnceInSortedOrder() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            TradePanel panel=new TradePanel();
            try {
                var clear=TradePanel.class.getDeclaredMethod("clearTradeState");clear.setAccessible(true);clear.invoke(panel);
                for(String name:java.util.List.of("Delta","Alpha","Charlie","Bravo")) {
                    Card card=new Card(name,"TST",Integer.toString(name.charAt(0)));card.setRarity("rare");card.setPrice("10");
                    panel.addFetchedCard(card,"","TST");
                }
                var tableField=TradePanel.class.getDeclaredField("cardTable");tableField.setAccessible(true);
                var table=(javax.swing.JTable)tableField.get(panel);
                var model=(TradeTableModel)table.getModel();
                table.getRowSorter().toggleSortOrder(2);
                table.setRowSelectionInterval(0,0);table.addRowSelectionInterval(2,2);
                assertEquals(2,table.getSelectedRowCount());
                model.setValueAt(true,1,0); // Alpha is both highlighted and checked.
                model.setValueAt(true,0,0); // Delta is checked only.
                var selected=TradePanel.class.getDeclaredMethod("selectedCardRows");selected.setAccessible(true);
                var rows=(java.util.List<?>)selected.invoke(panel);
                assertEquals(java.util.List.of(0,1,2),rows);
                var remove=TradePanel.class.getDeclaredMethod("removeCardRows",java.util.List.class);remove.setAccessible(true);remove.invoke(panel,rows);
                assertEquals(1,model.getRowCount());assertEquals("Bravo",model.getValueAt(0,2));
                var snapshot=TradePanel.class.getDeclaredMethod("snapshotDraft");snapshot.setAccessible(true);
                var draft=(com.cardpricer.model.TradeDraft)snapshot.invoke(panel);
                assertEquals(1,draft.lines().size());
                var selectAll=TradePanel.class.getDeclaredMethod("selectAllCards",boolean.class);selectAll.setAccessible(true);
                selectAll.invoke(panel,true);assertEquals(java.util.List.of(0),selected.invoke(panel));
                selectAll.invoke(panel,false);assertEquals(java.util.List.of(),selected.invoke(panel));
                clear.invoke(panel);
            } catch (ReflectiveOperationException e) {throw new RuntimeException(e);}
            finally {panel.disposeResources();TradeSessionService.clearAutosave();}
        });
    }
    @Test void reopeningTradePreservesPricesRatesPaymentAndRecoverableEditContext() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            TradePanel panel=new TradePanel();
            try {
                var original=TradeRepositoryTest.draft("TST");
                assertTrue(panel.editSavedTrade(original));
                var snapshot=TradePanel.class.getDeclaredMethod("snapshotDraft");snapshot.setAccessible(true);
                var draft=(com.cardpricer.model.TradeDraft)snapshot.invoke(panel);
                assertEquals(original.id(),draft.id());
                assertEquals(original.lines(),draft.lines());
                assertEquals(original.settle(),draft.settle());
                assertEquals(original.checkNumber(),draft.checkNumber());
                var field=TradePanel.class.getDeclaredField("tableModel");field.setAccessible(true);
                var model=(TradeTableModel)field.get(panel);
                model.setValueAt(5,0,4);
                draft=(com.cardpricer.model.TradeDraft)snapshot.invoke(panel);
                assertEquals(5,draft.lines().getFirst().quantity());
                assertEquals(new BigDecimal("16.00"),draft.settle().total());
                panel.flushDraftOnClose();
                assertEquals(original.revision(),TradeSessionService.loadEditingRevision());
                assertEquals(original.id(),TradeSessionService.loadDraft().id());
                assertEquals(5,TradeSessionService.loadDraft().lines().getFirst().quantity());
                var clear=TradePanel.class.getDeclaredMethod("clearTradeState");clear.setAccessible(true);clear.invoke(panel);
                assertFalse(panel.hasUnsavedCards());assertFalse(TradeSessionService.hasTypedDraft());
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { panel.disposeResources();TradeSessionService.clearAutosave(); }
        });
    }
    @Test void programmaticAddEditAndSnapshotUseOneModel() throws Exception {
        SwingUtilities.invokeAndWait(()->{
            TradePanel panel=new TradePanel();
            try {
                var bottom=((java.awt.BorderLayout)panel.getLayout()).getLayoutComponent(java.awt.BorderLayout.SOUTH);
                assertTrue(hasRetryButton(bottom));
                Card card=new Card("Integration Card","PLST","ARB-73★");card.setRarity("rare");card.setEtchedPrice("10");
                panel.addFetchedCard(card,"E","plst");
                var field=TradePanel.class.getDeclaredField("tableModel");field.setAccessible(true);
                var model=(TradeTableModel)field.get(panel);
                assertEquals(1,model.getRowCount());assertEquals("PLST ARB-73★E",model.getValueAt(0,1));
                model.setValueAt("LP",0,3);model.setValueAt(2,0,4);
                var snapshot=TradePanel.class.getDeclaredMethod("snapshotDraft");snapshot.setAccessible(true);
                var draft=(com.cardpricer.model.TradeDraft)snapshot.invoke(panel);
                assertEquals(new BigDecimal("8.00"),draft.lines().getFirst().valuation());
                assertEquals(new BigDecimal("8.00"),draft.settle().total());
                assertEquals(2,draft.lines().getFirst().quantity());
                var clear=TradePanel.class.getDeclaredMethod("clearTradeState");clear.setAccessible(true);clear.invoke(panel);
                assertEquals(0,model.getRowCount());
            } catch (ReflectiveOperationException e) {throw new RuntimeException(e);}
            finally {panel.disposeResources();}
        });
    }
    private static boolean hasRetryButton(java.awt.Component component) {
        if(component instanceof javax.swing.JButton button && button.getText().equals("Retry exports"))return true;
        if(component instanceof java.awt.Container container)
            for(var child:container.getComponents())if(hasRetryButton(child))return true;
        return false;
    }
}
