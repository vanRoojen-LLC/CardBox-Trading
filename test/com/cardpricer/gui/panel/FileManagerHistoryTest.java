package com.cardpricer.gui.panel;

import com.cardpricer.service.TradeHistoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class FileManagerHistoryTest {
    @TempDir Path temp;

    @Test void historyPagesSearchesAllReceiptsAndLoadsOnlySelectedPreviews() throws Exception {
        Path local=Files.createDirectory(temp.resolve("trades"));
        for(int i=0;i<125;i++) {
            String date=java.time.LocalDateTime.of(2026,1,1,0,0).plusDays(i).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Files.writeString(local.resolve(date+".txt"),"Customer Name: Customer "+i+"\nPayment Method: Check\nTotal Value: $24.00\nTotal Cards: 3\n"+(i==0 ? "Black Lotus" : "Ordinary"));
        }
        var session=new TradeHistoryService.Session(local.toString(),"");
        session.refreshIndex();
        var snapshot=session.page(new TradeHistoryService.Filter("","All",0),100,null,"");
        var holder=new AtomicReference<FileManagerPanel>();
        SwingUtilities.invokeAndWait(()->{
            var panel=new FileManagerPanel();holder.set(panel);
            set(panel,"historySession",session);
            accept(panel,snapshot);
            var table=field(panel,"historyTable",JTable.class);
            assertEquals(100,table.getRowCount());
            assertEquals("Trades: 125",field(panel,"historyStatsTotalTrades",JLabel.class).getText());
            assertTrue(field(panel,"contentCache",Map.class).isEmpty(),"Listing must not load previews");
            field(panel,"historyLoadMore",JButton.class).doClick();
        });
        var panel=holder.get();
        try {
            awaitEdt(()->field(panel,"historyTable",JTable.class).getRowCount()==125);
            SwingUtilities.invokeAndWait(()-> {
                assertFalse(field(panel,"historyLoadMore",JButton.class).isVisible());
                field(panel,"historySearchField",JTextField.class).setText("Black Lotus");
            });
            awaitEdt(()->field(panel,"historyTable",JTable.class).getRowCount()==1);
            SwingUtilities.invokeAndWait(()->{
                var table=field(panel,"historyTable",JTable.class);
                assertEquals("Customer 0",table.getValueAt(0,1));
                assertEquals("Trades: 1",field(panel,"historyStatsTotalTrades",JLabel.class).getText());
                assertTrue(field(panel,"contentCache",Map.class).isEmpty(),"Searching must not load previews");
                table.setRowSelectionInterval(0,0);
            });
            awaitEdt(()->field(panel,"histPrintBtn",JButton.class).isEnabled());
            var unchanged=session.page(new TradeHistoryService.Filter("black lotus","All",0),100,null,"");
            SwingUtilities.invokeAndWait(()->{
                var preview=field(panel,"historyPreviewArea",JTextArea.class);
                assertTrue(preview.getText().contains("Black Lotus"));
                assertEquals(1,field(panel,"contentCache",Map.class).size());
                preview.setCaretPosition(20);
                AtomicInteger events=new AtomicInteger();
                var table=field(panel,"historyTable",JTable.class);
                table.getModel().addTableModelListener(e->events.incrementAndGet());
                accept(panel,unchanged);
                assertEquals(0,events.get(),"Unchanged refresh must not rebuild the table");
                assertEquals(20,preview.getCaretPosition());
                assertEquals(0,table.getSelectedRow());
                field(panel,"historySearchField",JTextField.class).setText("");
            });
            awaitEdt(()->field(panel,"historyTable",JTable.class).getRowCount()==100);
            SwingUtilities.invokeAndWait(()-> {
                var table=field(panel,"historyTable",JTable.class);
                assertEquals(100,table.getRowCount());
                // Multiple changes on one EDT turn must never show an earlier worker's receipt.
                table.setRowSelectionInterval(0,0);
                table.setRowSelectionInterval(1,1);
            });
            awaitEdt(()->field(panel,"histPrintBtn",JButton.class).isEnabled());
            SwingUtilities.invokeAndWait(()->{
                var table=field(panel,"historyTable",JTable.class);
                String customer=table.getValueAt(table.getSelectedRow(),1).toString();
                assertTrue(field(panel,"historyPreviewArea",JTextArea.class).getText().contains("Customer Name: "+customer+"\n"));
                assertTrue(field(panel,"contentCache",Map.class).size()<=3);
            });
        } finally { SwingUtilities.invokeAndWait(panel::removeNotify); }
    }

    private static void awaitEdt(BooleanSupplier condition) throws Exception {
        long deadline=System.nanoTime()+5_000_000_000L;
        var ready=new AtomicBoolean();
        while(System.nanoTime()<deadline) {
            SwingUtilities.invokeAndWait(()->ready.set(condition.getAsBoolean()));
            if(ready.get()) return;
            Thread.sleep(10);
        }
        fail("History worker did not finish");
    }
    private static <T> T field(Object object,String name,Class<T> type) {
        try { Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return type.cast(f.get(object)); }
        catch(ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void set(Object object,String name,Object value) {
        try { Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);f.set(object,value); }
        catch(ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private static void accept(FileManagerPanel panel,TradeHistoryService.Page snapshot) {
        try {
            Method method=FileManagerPanel.class.getDeclaredMethod("acceptHistoryPage",TradeHistoryService.Page.class,boolean.class,String.class);
            method.setAccessible(true);method.invoke(panel,snapshot,false,field(panel,"historySearchField",JTextField.class).getText().trim().toLowerCase(java.util.Locale.ROOT));
        } catch(ReflectiveOperationException error) { throw new AssertionError(error); }
    }
}
