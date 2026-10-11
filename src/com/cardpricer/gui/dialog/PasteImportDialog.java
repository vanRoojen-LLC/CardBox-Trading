package com.cardpricer.gui.dialog;

import com.cardpricer.model.Card;
import com.cardpricer.model.ParsedCode;
import com.cardpricer.service.ScryfallApiService;
import com.cardpricer.service.ScryfallCatalogService;
import com.cardpricer.util.AppTheme;
import com.cardpricer.util.CardCodeParser;
import com.cardpricer.util.CardConstants;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modal dialog for pasting a list of card codes and bulk-importing them into
 * the trade table.
 *
 * <p>Phase 1 — input: user pastes codes; a live preview list shows ✓ valid /
 * ✗ invalid lines with zero API calls.  The "Import N Cards" button is enabled
 * only when at least one valid code is present.
 *
 * <p>Phase 2 — lookup: a {@link SwingWorker} resolves each card.  If the
 * local Scryfall catalog ({@link ScryfallCatalogService}) is loaded, cards are
 * looked up instantly; any misses fall back to individual Scryfall API calls
 * with the normal rate-limit delay.  Results stream in progressively.  When
 * complete, the provided {@code onImportComplete} callback is invoked on the EDT.
 * A "Close" button then dismisses the dialog.
 */
public class PasteImportDialog extends JDialog {

    /**
     * Result of fetching a single line from the paste list.
     *
     * @param parsed   the successfully parsed code
     * @param card     fetched card, or {@code null} on failure
     * @param errorMsg error description when {@code card} is {@code null}
     */
    public record FetchedResult(ParsedCode parsed, Card card, String errorMsg) {
        /** Returns {@code true} if the fetch succeeded. */
        public boolean ok() { return card != null && errorMsg == null; }
    }

    // ── Moxfield/ManaBox line pattern ─────────────────────────────────────────
    // Format: <qty> <card name> (<set>) <coll> [*F*|*E*|*S*]
    // e.g.:  1 Goblin Guide (PM19) 128s *F*
    //        2 Inquisition of Kozilek (CN2) 140
    //        1 Alpine Moon (PLST) M19-128
    private static final Pattern MOXFIELD_LINE_PATTERN = Pattern.compile(
            "^\\s*(\\d+)\\s+(.+?)\\s+\\((\\w+)\\)\\s+([^\\s*]+)(?:\\s+\\*([FES])\\*)?\\s*$",
            Pattern.CASE_INSENSITIVE);

    // ── Private helpers ───────────────────────────────────────────────────────

    private static final class ListItem {
        final String text;
        final boolean ok;
        ListItem(String text, boolean ok) { this.text = text; this.ok = ok; }
    }

    private static final class ItemRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);
            if (value instanceof ListItem item) {
                label.setText(item.text);
                if (!isSelected) {
                    label.setForeground(item.ok ? AppTheme.SUCCESS : AppTheme.DANGER);
                }
            }
            return label;
        }
    }

    // ── Fields ────────────────────────────────────────────────────────────────

    private final ScryfallApiService apiService;
    private final Consumer<List<FetchedResult>> onImportComplete;

    /** Valid parsed codes collected from the textarea — used by the fetch worker. */
    private final List<ParsedCode> validCodes = new ArrayList<>();

    // Phase 1 UI
    private JTabbedPane inputTabPane;
    private JTextArea inputArea;
    private JTextArea moxfieldArea;
    private JLabel previewSummaryLabel;
    private DefaultListModel<ListItem> previewModel;
    private JButton importButton;

    // Phase 2 UI
    private JLabel progressLabel;
    private JProgressBar progressBar;
    private DefaultListModel<ListItem> resultModel;
    private JButton closeButton;

    // Card layout switching
    private CardLayout cardLayout;
    private JPanel cardPanel;

    // ── Constructor ───────────────────────────────────────────────────────────

    /**
     * Creates the dialog.  Call {@link #setVisible(boolean) setVisible(true)} to show it.
     *
     * @param owner            parent window
     * @param apiService       Scryfall service for card fetching
     * @param onImportComplete callback invoked on the EDT when all fetches complete;
     *                         receives the full list of {@link FetchedResult} objects
     */
    public PasteImportDialog(Window owner, ScryfallApiService apiService,
                              Consumer<List<FetchedResult>> onImportComplete) {
        super(owner, "Paste Card List", ModalityType.APPLICATION_MODAL);
        this.apiService = apiService;
        this.onImportComplete = onImportComplete;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        buildUI();
        setSize(520, 560);
        setLocationRelativeTo(owner);
    }

    // ── UI construction ───────────────────────────────────────────────────────

    private void buildUI() {
        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(new EmptyBorder(14, 14, 14, 14));

        cardLayout = new CardLayout();
        cardPanel  = new JPanel(cardLayout);
        cardPanel.add(buildPhase1Panel(), "phase1");
        cardPanel.add(buildPhase2Panel(), "phase2");

        content.add(cardPanel, BorderLayout.CENTER);
        setContentPane(content);
    }

    private JPanel buildPhase1Panel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));

        // ── Input tabs ────────────────────────────────────────────────────────
        inputTabPane = new JTabbedPane();

        // Tab 1: Code List (existing format)
        JPanel codeListTab = new JPanel(new BorderLayout(0, 4));
        JLabel codeListLabel = new JLabel(
                "Paste one code per line (TDM 3, DMR 15f, PLST ARB 1, \u2026):");
        codeListLabel.setFont(codeListLabel.getFont().deriveFont(Font.BOLD, 13f));
        codeListTab.add(codeListLabel, BorderLayout.NORTH);
        inputArea = new JTextArea(8, 36);
        inputArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        inputArea.setLineWrap(false);
        JScrollPane inputScroll = new JScrollPane(inputArea);
        inputScroll.setPreferredSize(new Dimension(470, 160));
        codeListTab.add(inputScroll, BorderLayout.CENTER);
        inputTabPane.addTab("Code List", codeListTab);

        // Tab 2: Moxfield / ManaBox
        JPanel moxfieldTab = new JPanel(new BorderLayout(0, 4));
        JLabel moxfieldLabel = new JLabel(
                "<html>Paste a Moxfield or ManaBox export &mdash; <b>1 Goblin Guide (PM19) 128s *F*</b></html>");
        moxfieldLabel.setFont(moxfieldLabel.getFont().deriveFont(Font.BOLD, 13f));
        moxfieldTab.add(moxfieldLabel, BorderLayout.NORTH);
        moxfieldArea = new JTextArea(8, 36);
        moxfieldArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        moxfieldArea.setLineWrap(false);
        JScrollPane moxfieldScroll = new JScrollPane(moxfieldArea);
        moxfieldScroll.setPreferredSize(new Dimension(470, 160));
        moxfieldTab.add(moxfieldScroll, BorderLayout.CENTER);
        inputTabPane.addTab("Moxfield / ManaBox", moxfieldTab);

        // Re-run preview when tab switches
        inputTabPane.addChangeListener(e -> updatePreview());
        panel.add(inputTabPane, BorderLayout.NORTH);

        // ── Preview section ───────────────────────────────────────────────────
        JPanel previewSection = new JPanel(new BorderLayout(0, 4));
        previewSection.setBorder(AppTheme.sectionBorder("Preview"));

        previewSummaryLabel = new JLabel("Enter codes above to preview");
        previewSummaryLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        previewSection.add(previewSummaryLabel, BorderLayout.NORTH);

        previewModel = new DefaultListModel<>();
        JList<ListItem> previewList = new JList<>(previewModel);
        previewList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        previewList.setCellRenderer(new ItemRenderer());
        previewList.setVisibleRowCount(7);
        previewSection.add(new JScrollPane(previewList), BorderLayout.CENTER);
        panel.add(previewSection, BorderLayout.CENTER);

        // ── Buttons ───────────────────────────────────────────────────────────
        importButton = AppTheme.primaryButton("Import 0 Cards");
        importButton.setEnabled(false);
        importButton.addActionListener(e -> startFetching());

        JButton cancelButton = AppTheme.secondaryButton("Cancel");
        cancelButton.addActionListener(e -> dispose());

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonRow.add(importButton);
        buttonRow.add(cancelButton);
        panel.add(buttonRow, BorderLayout.SOUTH);

        // ── DocumentListeners for live preview ────────────────────────────────
        DocumentListener dl = new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { updatePreview(); }
            @Override public void removeUpdate(DocumentEvent e)  { updatePreview(); }
            @Override public void changedUpdate(DocumentEvent e) { updatePreview(); }
        };
        inputArea.getDocument().addDocumentListener(dl);
        moxfieldArea.getDocument().addDocumentListener(dl);

        return panel;
    }

    private JPanel buildPhase2Panel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));

        // ── Progress area ─────────────────────────────────────────────────────
        JPanel progressSection = new JPanel(new BorderLayout(0, 4));
        progressLabel = new JLabel("Preparing\u2026");
        progressLabel.setFont(progressLabel.getFont().deriveFont(Font.BOLD, 13f));
        progressBar   = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressSection.add(progressLabel, BorderLayout.NORTH);
        progressSection.add(progressBar,   BorderLayout.CENTER);
        panel.add(progressSection, BorderLayout.NORTH);

        // ── Results list ──────────────────────────────────────────────────────
        resultModel = new DefaultListModel<>();
        JList<ListItem> resultList = new JList<>(resultModel);
        resultList.setFont(new Font("Monospaced", Font.PLAIN, 12));
        resultList.setCellRenderer(new ItemRenderer());
        JScrollPane resultScroll = new JScrollPane(resultList);
        resultScroll.setBorder(AppTheme.sectionBorder("Results"));
        panel.add(resultScroll, BorderLayout.CENTER);

        // ── Close button ──────────────────────────────────────────────────────
        closeButton = AppTheme.primaryButton("Close");
        closeButton.setEnabled(false);
        closeButton.addActionListener(e -> dispose());

        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttonRow.add(closeButton);
        panel.add(buttonRow, BorderLayout.SOUTH);

        return panel;
    }

    // ── Preview logic ─────────────────────────────────────────────────────────

    private void updatePreview() {
        previewModel.clear();
        validCodes.clear();

        boolean isMoxfield = inputTabPane.getSelectedIndex() == 1;
        String text = isMoxfield ? moxfieldArea.getText() : inputArea.getText();

        if (text.isBlank()) {
            previewSummaryLabel.setText("Enter codes above to preview");
            importButton.setText("Import 0 Cards");
            importButton.setEnabled(false);
            return;
        }

        String[] lines = text.split("\n");
        int valid = 0, invalid = 0;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;

            if (isMoxfield) {
                List<ParsedCode> codes = parseMoxfieldLine(trimmed);
                if (!codes.isEmpty()) {
                    ParsedCode first = codes.get(0);
                    String finishDisplay = switch (first.finish) {
                        case "F" -> "foil";
                        case "E" -> "etched";
                        case "S" -> "surge foil";
                        default  -> "normal";
                    };
                    int qty = codes.size();
                    String setDisplay = first.setCode.equalsIgnoreCase("plst")
                            ? "PLST " + first.collectorNumber
                            : first.setCode.toUpperCase() + " " + first.collectorNumber;
                    String display = (qty > 1 ? qty + "\u00d7 " : "") + setDisplay
                            + "  \u2192  " + finishDisplay;
                    previewModel.addElement(new ListItem("\u2713 " + display, true));
                    validCodes.addAll(codes);
                    valid += qty;
                } else {
                    previewModel.addElement(new ListItem(
                            "\u2717 \"" + trimmed + "\"   cannot parse", false));
                    invalid++;
                }
            } else {
                ParsedCode parsed = CardCodeParser.parse(trimmed);
                if (parsed != null) {
                    String finishDisplay = switch (parsed.finish) {
                        case "F" -> "foil";
                        case "E" -> "etched";
                        case "S" -> "surge foil";
                        default  -> "normal";
                    };
                    previewModel.addElement(new ListItem(
                            "\u2713 " + trimmed + "   \u2192  " + finishDisplay, true));
                    validCodes.add(parsed);
                    valid++;
                } else {
                    previewModel.addElement(new ListItem(
                            "\u2717 \"" + trimmed + "\"   cannot parse", false));
                    invalid++;
                }
            }
        }

        if (valid + invalid == 0) {
            previewSummaryLabel.setText("Enter codes above to preview");
        } else {
            previewSummaryLabel.setText("Preview \u2014 " + valid + " valid, " + invalid + " invalid:");
        }
        importButton.setText("Import " + valid + " Card" + (valid == 1 ? "" : "s"));
        importButton.setEnabled(valid > 0);
    }

    // ── Moxfield / ManaBox parsing ────────────────────────────────────────────

    /**
     * Parses a single Moxfield / ManaBox export line into one or more
     * {@link ParsedCode} entries (quantity-expanded).
     *
     * <p>Conversion rules:
     * <ul>
     *   <li>PLST collector numbers (e.g. {@code M19-128}) are passed through as-is.</li>
     *   <li>Promo sets and collector suffixes are retained for exact printing lookup
     *       (e.g. {@code PEOE 210s}). POS aliases apply only when exporting the trade.</li>
     *   <li>{@code *F*} → foil, {@code *E*} → etched, {@code *S*} → surge foil.</li>
     * </ul>
     *
     * @param line one trimmed line from the Moxfield export
     * @return list of {@link ParsedCode} (size == quantity); empty list if unparseable
     */
    static List<ParsedCode> parseMoxfieldLine(String line) {
        Matcher m = MOXFIELD_LINE_PATTERN.matcher(line);
        if (!m.matches()) return List.of();

        int qty        = Integer.parseInt(m.group(1));
        // group 2 = card name (not used for lookup)
        String rawSet  = m.group(3).toUpperCase(java.util.Locale.ROOT);
        String rawColl = m.group(4);
        String finishChar = m.group(5); // null if no *X* tag

        String finish = finishChar == null ? "" : finishChar.toUpperCase(java.util.Locale.ROOT);
        String collNum = rawColl.toLowerCase(java.util.Locale.ROOT);

        ParsedCode parsed = CardCodeParser.forLookup(rawSet, collNum, finish);
        return Collections.nCopies(qty, parsed);
    }

    // ── Fetch logic ───────────────────────────────────────────────────────────

    private void startFetching() {
        if (validCodes.isEmpty()) return;

        // Snapshot the valid codes so the worker owns them
        List<ParsedCode> codesToFetch = new ArrayList<>(validCodes);

        // Switch to phase 2
        cardLayout.show(cardPanel, "phase2");
        ScryfallCatalogService catalog = ScryfallCatalogService.getInstance();
        if (catalog.isLoaded()) {
            progressLabel.setText("Looking up " + codesToFetch.size()
                    + " card" + (codesToFetch.size() == 1 ? "" : "s") + " in catalog\u2026");
        } else {
            progressLabel.setText("Fetching 0 / " + codesToFetch.size() + "\u2026");
        }
        progressBar.setValue(0);

        activeImport = new SwingWorker<List<FetchedResult>, FetchedResult>() {

            @Override
            protected List<FetchedResult> doInBackground() throws Exception {
                List<FetchedResult> allResults = new ArrayList<>();
                boolean lastWasApiCall = false;

                for (ParsedCode parsed : codesToFetch) {
                    if (isCancelled()) break;

                    // Throttle only between consecutive API calls
                    if (lastWasApiCall) Thread.sleep(CardConstants.API_RATE_LIMIT_MS);

                    // Try the local catalog first (O(1), instant)
                    java.util.Optional<Card> hit =
                            catalog.lookup(parsed.setCode, parsed.collectorNumber);
                    if (hit.isPresent()) {
                        FetchedResult r = new FetchedResult(parsed, hit.get(), hasPrice(hit.get(),parsed.finish) ? null : "No price for selected finish");
                        allResults.add(r);
                        publish(r);
                        lastWasApiCall = false;
                    } else {
                        // Catalog miss — fall back to Scryfall API
                        try {
                            Card card = apiService.fetchCard(
                                    parsed.setCode, parsed.collectorNumber);
                            FetchedResult r = new FetchedResult(parsed, card, hasPrice(card,parsed.finish) ? null : "No price for selected finish");
                            allResults.add(r);
                            publish(r);
                        } catch (Exception e) {
                            FetchedResult r = new FetchedResult(parsed, null, e.getMessage());
                            allResults.add(r);
                            publish(r);
                        }
                        lastWasApiCall = true;
                    }
                }
                return allResults;
            }

            @Override
            protected void process(List<FetchedResult> chunks) {
                if (isCancelled() || !isDisplayable()) return;
                for (FetchedResult result : chunks) {
                    String label;
                    if (result.ok()) {
                        BigDecimal price = getDisplayPrice(result);
                        String finishTag = finishTag(result.parsed().finish);
                        label = "\u2713 " + result.card().getName()
                                + finishTag
                                + "   $" + String.format("%.2f", price);
                        resultModel.addElement(new ListItem(label, true));
                    } else {
                        String code = result.parsed().setCode
                                + " " + result.parsed().collectorNumber;
                        label = "\u2717 " + code + " \u2014 "
                                + (result.errorMsg() != null ? result.errorMsg() : "fetch failed");
                        resultModel.addElement(new ListItem(label, false));
                    }

                    int done  = resultModel.getSize();
                    int total = codesToFetch.size();
                    progressBar.setValue(done * 100 / total);
                    progressLabel.setText("Fetching " + done + " / " + total + "\u2026");
                }
            }

            @Override
            protected void done() {
                if (isCancelled() || !isDisplayable()) return;
                try {
                    List<FetchedResult> allResults = get();
                    long ok = allResults.stream().filter(FetchedResult::ok).count();
                    progressLabel.setText("Done \u2014 " + ok + " of "
                            + allResults.size() + " fetched successfully.");
                    progressBar.setValue(100);
                    if (onImportComplete != null) {
                        onImportComplete.accept(allResults);
                    }
                } catch (CancellationException ignored) {
                    progressLabel.setText("Import cancelled.");
                } catch (Exception e) {
                    progressLabel.setText("Error: " + e.getMessage());
                }
                closeButton.setEnabled(true);
            }

        };
        com.cardpricer.service.TaskCoordinator.execute(activeImport);
    }

    private SwingWorker<List<FetchedResult>, FetchedResult> activeImport;
    @Override public void dispose() { if (activeImport!=null) activeImport.cancel(true); super.dispose(); }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static boolean hasPrice(Card card,String finish) {
        return switch (finish) { case "E" -> card.hasEtchedPrice(); case "F","S" -> card.hasFoilPrice(); default -> card.hasNormalPrice(); };
    }

    private static BigDecimal getDisplayPrice(FetchedResult r) {
        Card card = r.card();
        return switch (r.parsed().finish) {
            case "F", "S" -> card.hasFoilPrice()   ? card.getFoilPriceAsBigDecimal()   : BigDecimal.ZERO;
            case "E"      -> card.hasEtchedPrice()  ? card.getEtchedPriceAsBigDecimal() : BigDecimal.ZERO;
            default       -> card.hasNormalPrice()  ? card.getPriceAsBigDecimal()       : BigDecimal.ZERO;
        };
    }

    private static String finishTag(String finish) {
        return switch (finish) {
            case "F" -> " (Foil)";
            case "E" -> " (Etched)";
            case "S" -> " (Surge Foil)";
            default  -> "";
        };
    }
}
