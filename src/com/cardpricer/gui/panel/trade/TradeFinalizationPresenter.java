package com.cardpricer.gui.panel.trade;
import com.cardpricer.model.TradeDraft;
import com.cardpricer.service.*;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Approval/progress view and background application command; no trade state lives in the dialog. */
public final class TradeFinalizationPresenter {
    public void approve(Window owner,TradeDraft draft,Path ledger,Path output,Consumer<Integer> saved) {
        approve(owner,draft,ledger,output,null,saved);
    }
    public void approve(Window owner,TradeDraft draft,Path ledger,Path output,Long editingRevision,Consumer<Integer> saved) {
        String message=editingRevision==null ? "Approve trade?" : "Save corrections to this trade?\nThe original is kept. POS inventory status will need review again.\nIf already entered in your POS, reconcile the correction before importing again.";
        if (JOptionPane.showConfirmDialog(owner,message+"\nPayout: $"+draft.settle().total().toPlainString()
                +"\nPayment: "+draft.payment(),editingRevision==null ? "Confirm trade" : "Save changes",JOptionPane.YES_NO_OPTION)!=JOptionPane.YES_OPTION)return;
        JDialog progress=new JDialog(owner,"Saving trade",Dialog.ModalityType.APPLICATION_MODAL);
        progress.add(new JLabel("Saving the approved trade and preparing its files..."));
        progress.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);progress.pack();progress.setLocationRelativeTo(owner);
        SwingWorker<Integer,Void> worker=new SwingWorker<>() {
            protected Integer doInBackground() throws Exception {
                TradeSessionService.flush();
                String shared=com.cardpricer.gui.panel.PreferencesPanel.getSharedTradesFolder();
                var repository=new TradeRepository(ledger);
                repository.saveRecoveryDraft(draft,editingRevision);
                var service=new TradeApplicationService(repository,output,shared.isBlank() ? null : Path.of(shared));
                return editingRevision==null ? service.finalizeTrade(draft) : service.updateTrade(draft,editingRevision);
            }
            protected void done() {
                progress.dispose();
                try {saved.accept(get());}
                catch(Exception e) {
                    Throwable cause=e;while(cause.getCause()!=null)cause=cause.getCause();
                    JOptionPane.showMessageDialog(owner,"Could not finish saving: "+cause.getMessage()
                        +"\nThe draft is retained. If the connection failed during a shared save, refresh History to check whether the revision was saved.","Save failed",JOptionPane.ERROR_MESSAGE);
                }
            }
        };
        TaskCoordinator.execute(worker);progress.setVisible(true);
    }
}
