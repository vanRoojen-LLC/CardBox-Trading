package com.cardpricer.gui.panel.trade;

import com.cardpricer.service.TradeRepository;
import com.cardpricer.service.TradeSessionService;
import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Recovery UI and storage tasks kept outside the trade editor. */
public final class TradeRecoveryPresenter {
    private TradeRecoveryPresenter() {}

    public static void park(Component owner,Runnable capture,Runnable startNew) {
        try { capture.run(); }
        catch(RuntimeException failure) { error(owner,failure);return; }
        run(owner,"Saving draft",()-> { TradeSessionService.flush();return true; },ignored -> startNew.run());
    }

    public static void choose(Component owner,UUID current,Runnable capture,Consumer<TradeRepository.RecoveryDraft> restore) {
        try { capture.run(); }
        catch(RuntimeException failure) { error(owner,failure);return; }
        run(owner,"Loading drafts",()-> { TradeSessionService.flush();return TradeSessionService.drafts(); },drafts -> {
            var choices=drafts.stream().filter(d->!d.id().equals(current)).toArray(TradeRepository.DraftSummary[]::new);
            if(choices.length==0) { JOptionPane.showMessageDialog(owner,"No other saved drafts.");return; }
            var list=new JList<>(choices);list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);list.setSelectedIndex(0);
            var scroll=new JScrollPane(list);scroll.setPreferredSize(new Dimension(720,230));
            int action=JOptionPane.showOptionDialog(owner,scroll,"Saved drafts",JOptionPane.DEFAULT_OPTION,
                    JOptionPane.PLAIN_MESSAGE,null,new String[]{"Resume draft","Delete draft","Cancel"},"Resume draft");
            var selected=list.getSelectedValue();
            if(selected==null) return;
            if(action==0) run(owner,"Restoring draft",()->TradeSessionService.load(selected.id()),restore);
            else if(action==1 && JOptionPane.showConfirmDialog(owner,"Delete this draft for "+selected.customer()+"?",
                    "Delete draft",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION)
                run(owner,"Deleting draft",()-> { TradeSessionService.discard(selected.id());return true; },ignored -> {});
        });
    }

    public static void offer(Component owner,Consumer<TradeRepository.RecoveryDraft> restore,java.util.function.BooleanSupplier editorEmpty) {
        if(GraphicsEnvironment.isHeadless()) return;
        new SwingWorker<List<TradeRepository.DraftSummary>,Void>() {
            protected List<TradeRepository.DraftSummary> doInBackground() { return TradeSessionService.drafts(); }
            protected void done() {
                try {
                    if(!get().isEmpty() && editorEmpty.getAsBoolean() && JOptionPane.showConfirmDialog(owner,
                            "Saved drafts are available. Open the draft chooser?","Recover work",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION)
                        choose(owner,null,()->{},restore);
                } catch(Exception failure) { error(owner,failure); }
            }
        }.execute();
    }

    private static <T> void run(Component owner,String title,Callable<T> task,Consumer<T> completed) {
        var dialog=new JDialog(SwingUtilities.getWindowAncestor(owner),title,Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.add(new JLabel(title+"…",SwingConstants.CENTER));dialog.setSize(320,110);dialog.setLocationRelativeTo(owner);
        new SwingWorker<T,Void>() {
            protected T doInBackground() throws Exception { return task.call(); }
            protected void done() {
                dialog.dispose();
                try { completed.accept(get()); } catch(Exception failure) { error(owner,failure); }
            }
        }.execute();
        dialog.setVisible(true);
    }
    private static void error(Component owner,Exception failure) {
        Throwable cause=failure;while(cause.getCause()!=null) cause=cause.getCause();
        JOptionPane.showMessageDialog(owner,cause.getMessage(),"Draft recovery",JOptionPane.ERROR_MESSAGE);
    }
}
