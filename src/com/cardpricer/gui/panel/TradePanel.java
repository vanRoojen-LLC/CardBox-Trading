package com.cardpricer.gui.panel;

import com.cardpricer.gui.CardImagePopup;
import com.cardpricer.gui.ShortcutHelpDialog;
import com.cardpricer.gui.dialog.CardSearchDialog;
import com.cardpricer.gui.dialog.PasteImportDialog;
import com.cardpricer.gui.dialog.PasteImportDialog.FetchedResult;
import com.cardpricer.gui.dialog.PriceCheckDialog;
import com.cardpricer.gui.panel.trade.PaymentTypePanel;
import com.cardpricer.gui.panel.trade.TradeSummaryPanel;
import com.cardpricer.model.Card;
import com.cardpricer.model.ParsedCode;
import com.cardpricer.model.TradeItem;
import com.cardpricer.service.BuyRateService;
import com.cardpricer.service.PricingService;
import com.cardpricer.service.ScryfallApiService;
import com.cardpricer.service.ScryfallCatalogService;
import com.cardpricer.service.TradeReceivingExportService;
import com.cardpricer.service.TradeSessionService;
import com.cardpricer.util.AppTheme;
import com.cardpricer.util.CardCodeParser;
import com.cardpricer.util.PosMoneyField;
import com.cardpricer.util.CardConstants;
import com.cardpricer.util.VintageUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeEvent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TableColumnModelEvent;
import javax.swing.event.TableColumnModelListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import javax.imageio.ImageIO;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.function.BiConsumer;

/**
 * Quick-entry panel for receiving cards from trades and purchases.
 *
 * <p>Cards are entered via a code field using the formats:
 * <ul>
 *   <li>{@code TDM 3} — normal finish</li>
 *   <li>{@code TDM 3f} — foil finish</li>
 *   <li>{@code TDM 3e} — etched finish</li>
 *   <li>{@code PIP 882s} — surge foil finish</li>
 *   <li>{@code PEOE 210sf} — foil of promo collector number 210s</li>
 *   <li>{@code PLST ARB 1} — The List reprint</li>
 *   <li>{@code misc} or {@code MTG misc} — manual entry with custom name and price</li>
 * </ul>
 *
 * <p>Prices are fetched from the Scryfall API, rounded via {@link com.cardpricer.service.PricingService},
 * and can be exported to a POS CSV file or saved as a plain-text trade receipt.
 * Keyboard shortcuts: {@code Ctrl+F}/{@code F2} = search by name, {@code Numpad +} = duplicate row,
 * {@code Ctrl+Z} = undo last add, {@code F1} = shortcut help.
 */
public class TradePanel extends JPanel {

    // Old keys also contained automatic layout widths; start from the original proportions.
    private static final String COLUMN_WIDTH_PREF = "trade.table.manual.col.";

    // Help dialog content
    private static final String   HELP_TITLE = "Trade Panel — Help";
    private static final String[] HELP_COLS  = {"Shortcut / Code", "Description"};
    private static final String[][] HELP_ROWS = {
        {"--- Keyboard Shortcuts", ""},
        {"Enter",          "Add card from the code field"},
        {"+ / Numpad +",   "Duplicate the selected row"},
        {"Ctrl+Z",         "Undo the last added card"},
        {"Ctrl+F / F2",    "Search for a card by name"},
        {"/",              "Jump to card code field"},
        {"Ctrl+Space",              "Quick price check (floats alongside trade)"},
        {"Ctrl+L / [Paste List]",   "Paste a list of card codes to import at once"},
        {"F4 / [Vintage]", "Show vintage set code reference"},
        {"F1 / [?]",       "Show this help dialog"},
        {"--- Code Formats", ""},
        {"TDM 3",          "Normal finish (set code + number)"},
        {"TDM 3f",         "Foil finish"},
        {"TDM 3e",         "Etched finish"},
        {"PIP 882s",       "Surge foil — use the surge printing's collector number"},
        {"40K 129s",       "40K surge foil (looks up collector 129★)"},
        {"PEOE 219sf",     "Promo 219s, foil (also PEOE 219s F)"},
        {"PEOE 219pf",     "Promo 219p, foil"},
        {"PLST ARB 1",     "The List card (PLST + original set + number)"},
        {"misc / MTG misc", "Manual entry — prompts for name & price"},
    };

    private final ScryfallApiService apiService;
    private final TradeReceivingExportService exportService;
    private final PricingService pricingService = new PricingService();
    private final BuyRateService buyRateService = new BuyRateService();
    private int lastKnownBuyRateGen = BuyRateService.getSaveGeneration();

    private java.util.UUID draftId = java.util.UUID.randomUUID();
    private long draftRevision;
    private Long editingRevision;
    private long draftChangeGeneration;
    private final JLabel draftSaveStatus = new JLabel("No active draft");
    private final java.util.Map<java.util.UUID, com.cardpricer.model.TradeLine> savedLines = new java.util.HashMap<>();
    private final JLabel editingLabel = new JLabel();
    private final JButton cancelEditButton = new JButton("Cancel edit");
    private long draftGeneration;
    private long previewGeneration;
    private com.cardpricer.gui.panel.trade.TradeEntryPresenter entryPresenter;
    private boolean batchingCards;
    private javax.swing.Timer syncTimer;
    private final JLabel syncStatusLabel = new JLabel("Trades saved locally; shared sync starts in the background.");
    private List<TradeItem> receivedCards;
    private List<String> cardConditions; // Track condition for each card
    private List<BuyRateService.PayoutResult> rowPayouts;
    private boolean isRefreshingSummary = false;

    // Input field
    private JTextField cardCodeField;
    private JLabel cardPreviewLabel;

    // Table
    private JTable cardTable;
    private com.cardpricer.gui.panel.trade.TradeTableModel tableModel;

    // Summary and payment sub-panels
    private final TradeSummaryPanel summaryPanel = new TradeSummaryPanel();
    private PaymentTypePanel paymentTypePanel;
    private JPanel entryTop;
    private JPanel entryWorkspace;
    private JPanel customerFields;
    private JToggleButton detailsToggle;
    private Boolean detailsExpandedOverride;

    // Trade info fields
    private JTextField traderNameField;
    private JTextField customerNameField;
    private JTextField driversLicenseField;
    private JTextField checkNumberField;

    // Preview card
    private Card previewCard;
    private String lastPreviewCode; // Track what code the preview is showing
    private String previewFinish;
    /** Stores the raw set code from the user's input (e.g. "plst") to distinguish
     *  PLST entries so the table can show "PLST ARB 1" while saving "ARB 1". */
    private String previewOriginalSetCode = "";

    /** Lazy-initialized hover image popup. */
    private CardImagePopup imagePopup;

    private String lastSavedTxtPath = null;
    private JButton saveExportBtn;

    // ── Feature: Autosave ─────────────────────────────────────────────────────
    private Timer autosaveTimer;

    // ── Feature: Undo ────────────────────────────────────────────────────────
    private TradeItem lastAddedItem = null;
    private int lastAddedRow = -1;
    private JButton undoBtn;

    /**
     * Comparator for price columns that parses {@code "$1.23"}-style strings into
     * {@link BigDecimal} values for numeric ordering. Falls back to lexicographic
     * comparison if parsing fails.
     */
    private static final java.util.Comparator<String> PRICE_COMPARATOR = (s1, s2) -> {
        try {
            BigDecimal p1 = new BigDecimal(s1.replace("$", "").replace(",", "").trim());
            BigDecimal p2 = new BigDecimal(s2.replace("$", "").replace(",", "").trim());
            return p1.compareTo(p2);
        } catch (Exception e) {
            return s1.compareTo(s2);
        }
    };

    /**
     * Natural-sort comparator for the Code column: treats embedded digit runs as numbers
     * so "TDM 2" sorts before "TDM 11" instead of after.
     */
    private static final java.util.Comparator<String> NATURAL_SORT_COMPARATOR = (a, b) -> {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int ni = i, nj = j;
                while (ni < a.length() && Character.isDigit(a.charAt(ni))) ni++;
                while (nj < b.length() && Character.isDigit(b.charAt(nj))) nj++;
                int diff = Integer.compare(
                        Integer.parseInt(a.substring(i, ni)),
                        Integer.parseInt(b.substring(j, nj)));
                if (diff != 0) return diff;
                i = ni; j = nj;
            } else {
                if (ca != cb) return Character.compare(ca, cb);
                i++; j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    };

    /**
     * Constructs the Trade Receiving panel, initialises all sub-panels and UI components,
     * and registers global keyboard shortcuts (Numpad+, Ctrl+Z, F1).
     */
    public TradePanel() {
        this.apiService = new ScryfallApiService();
        entryPresenter=new com.cardpricer.gui.panel.trade.TradeEntryPresenter(ScryfallCatalogService.getInstance(),apiService);
        this.exportService = new TradeReceivingExportService();


        // paymentTypePanel must be initialised before createInputPanel() is called
        paymentTypePanel = new PaymentTypePanel(this::onPaymentSelectionChanged);

        setLayout(new BorderLayout(12, 8));
        setBorder(new EmptyBorder(14, 20, 12, 20));
        JPanel header = AppTheme.transparent(new BorderLayout(12, 0));
        header.add(AppTheme.panelHeader("Trades", "Receive and price trade-ins"), BorderLayout.CENTER);
        detailsToggle = new JToggleButton("Customer details");
        detailsToggle.setToolTipText("Show team member, customer, identification and check fields");
        detailsToggle.addActionListener(e -> {
            detailsExpandedOverride = detailsToggle.isSelected(); revalidate(); repaint();
        });
        JPanel headerActions = AppTheme.transparent(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        headerActions.add(detailsToggle); header.add(headerActions, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        JPanel topWrapper = new JPanel(new BorderLayout(0, 10));
        topWrapper.add(createInputPanel(), BorderLayout.CENTER);

        JPanel lowerPanel = new JPanel(new BorderLayout(10, 10));
        lowerPanel.add(createTablePanel(), BorderLayout.CENTER);

        JScrollPane entryScroll = com.cardpricer.gui.ScrollablePage.wrap(topWrapper);
        entryScroll.setBorder(BorderFactory.createEmptyBorder());
        entryScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        entryScroll.getVerticalScrollBar().setUnitIncrement(24);
        entryScroll.setMinimumSize(new Dimension(0, 100));
        entryTop = topWrapper;
        entryWorkspace = new JPanel(new BorderLayout(0, 10)) {
            @Override public void doLayout() {
                int preferred = entryTop.getPreferredSize().height;
                int tableSpace = com.formdev.flatlaf.util.UIScale.scale(160);
                int height = Math.max(60, Math.min(preferred, getHeight() - tableSpace - 10));
                entryScroll.setPreferredSize(new Dimension(getWidth(), height));
                super.doLayout();
            }
        };
        entryWorkspace.add(entryScroll, BorderLayout.NORTH);
        entryWorkspace.add(lowerPanel, BorderLayout.CENTER);
        add(entryWorkspace, BorderLayout.CENTER);
        JButton retryExports = AppTheme.secondaryButton("Retry exports");
        retryExports.setFont(AppTheme.FONT_SMALL);
        retryExports.setMargin(new Insets(4, 10, 4, 10));
        syncStatusLabel.setFont(AppTheme.FONT_SMALL);
        syncStatusLabel.setForeground(AppTheme.muted());
        retryExports.addActionListener(e -> retryPendingExports());
        JPanel bottom = new JPanel(new BorderLayout(8, 4));
        JPanel syncRow = new JPanel(new BorderLayout(8, 0));
        syncRow.add(syncStatusLabel, BorderLayout.CENTER);
        syncRow.add(retryExports, BorderLayout.EAST);
        bottom.add(syncRow, BorderLayout.NORTH);
        bottom.add(createBottomPanel(), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);

        // Numpad + duplicates the most recently selected row from anywhere in the panel
        InputMap panelIM = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap panelAM = getActionMap();
        panelIM.put(KeyStroke.getKeyStroke(KeyEvent.VK_ADD, 0), "globalDuplicate");
        panelAM.put("globalDuplicate", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                // keyTyped handles '+' when the code field has focus; skip here to avoid double-fire
                if (!cardCodeField.isFocusOwner()) {
                    duplicateSelectedCard();
                }
            }
        });

        // Ctrl+Z → undo last added card
        panelIM.put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "globalUndo");
        panelAM.put("globalUndo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                undoLastCard();
            }
        });

        // F1 → shortcut help dialog
        panelIM.put(KeyStroke.getKeyStroke(KeyEvent.VK_F1, 0), "showHelp");
        panelAM.put("showHelp", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                ShortcutHelpDialog.show(SwingUtilities.getWindowAncestor(TradePanel.this),
                        HELP_TITLE, HELP_COLS, HELP_ROWS);
            }
        });

        // F4 → vintage set reference dialog
        panelIM.put(KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), "showVintage");
        panelAM.put("showVintage", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showVintageReference();
            }
        });

        // / → jump to card code field
        panelIM.put(KeyStroke.getKeyStroke('/'), "focusCodeField");
        panelAM.put("focusCodeField", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                cardCodeField.requestFocusInWindow();
            }
        });

        // Ctrl+Space → quick price check dialog
        panelIM.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK), "priceCheck");
        panelAM.put("priceCheck", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                PriceCheckDialog.show(SwingUtilities.getWindowAncestor(TradePanel.this),
                        (card, finish) -> addFetchedCard(card, finish, card.getSetCode()));
            }
        });

        // Ctrl+L → paste import dialog
        panelIM.put(KeyStroke.getKeyStroke(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK), "pasteImport");
        panelAM.put("pasteImport", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                showPasteImportDialog();
            }
        });

        SwingUtilities.invokeLater(() -> cardCodeField.requestFocusInWindow());

        // F5: offer crash-recovery restore, then start 60-second autosave timer
        SwingUtilities.invokeLater(this::offerSessionRestore);
        autosaveTimer = new Timer(2_000, e -> { try { performAutosave(); } catch(RuntimeException ignored) { /* Visible save status retains the error. */ } });
        autosaveTimer.setRepeats(false);
        autosaveTimer.start();
        syncTimer=new Timer(30_000,e -> {
            com.cardpricer.service.TaskCoordinator.submit(buyRateService::pollSharedFolder);
            TradeReceivingExportService.syncMissingToSharedFolder();
            syncStatusLabel.setText("Rates: "+buyRateService.getSyncStatus()+"; trades: "+TradeReceivingExportService.getSharedSyncStatus());
        });
        syncTimer.start();
        javax.swing.event.DocumentListener draftChanged=new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { markDraftChanged(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { markDraftChanged(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { markDraftChanged(); }
        };
        for (JTextField field: new JTextField[]{traderNameField,customerNameField,driversLicenseField,checkNumberField})
            field.getDocument().addDocumentListener(draftChanged);
        paymentTypePanel.onPaymentEdited(this::markDraftChanged);

        // Reload buy rates when this panel becomes visible (Preferences or shared file may have changed)
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing() && imagePopup != null) {
                imagePopup.hide();
            }
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                com.cardpricer.service.TaskCoordinator.submit(buyRateService::pollSharedFolder);
                int gen = BuyRateService.getSaveGeneration();
                if (gen != lastKnownBuyRateGen) {
                    lastKnownBuyRateGen = gen;
                    buyRateService.refreshFromPublished();
                    tableModel.invalidatePayouts();
                    refreshSummary();
                }
            }
        });
    }

    // -------------------------------------------------------------------------
    // Payment selection callback
    // -------------------------------------------------------------------------

    /** Called by PaymentTypePanel whenever the payout selection changes. */
    private void onPaymentSelectionChanged() {
        String type = paymentTypePanel.getPaymentType();
        boolean needsCheck = "check".equals(type) || "partial".equals(type);
        if (needsCheck) detailsExpandedOverride = true;
        checkNumberField.setEnabled(needsCheck);
        if (!needsCheck) {
            checkNumberField.setText("");
        }
        if (saveExportBtn != null) {
            saveExportBtn.setText(editingRevision!=null ? "Save changes" : "inventory".equals(type) ? "Confirm trade" : "Save & export");
        }
        resizeEntryArea();
        refreshSummary();
    }

    @Override public void doLayout() {
        if (customerFields != null) {
            boolean compact = getHeight() < 650;
            boolean filled = !traderNameField.getText().isBlank() || !customerNameField.getText().isBlank()
                    || !driversLicenseField.getText().isBlank() || !checkNumberField.getText().isBlank();
            boolean expanded = detailsExpandedOverride != null ? detailsExpandedOverride : !compact || filled;
            customerFields.setVisible(expanded); detailsToggle.setSelected(expanded);
            summaryPanel.setCompact(compact);
        }
        super.doLayout();
    }

    /** Expand for split tender when space permits; compact windows can scroll the form. */
    private void resizeEntryArea() {
        if (entryWorkspace != null) { entryWorkspace.revalidate(); entryWorkspace.repaint(); }
    }

    // -------------------------------------------------------------------------
    // Panel construction
    // -------------------------------------------------------------------------

    private JPanel createInputPanel() {
        JPanel panel = AppTheme.surface(new BorderLayout(0, 10), 12);
        JPanel tradeInfoPanel = AppTheme.transparent(new BorderLayout(0, 10));
        JPanel fields = new com.cardpricer.gui.ResponsiveGrid(4, 150, 12);
        customerFields = fields;
        traderNameField = new JTextField(10);
        customerNameField = new JTextField(10);
        driversLicenseField = new JTextField(10);
        checkNumberField = new JTextField(10);
        checkNumberField.setEnabled(false);
        fields.add(labeledField("Team member", traderNameField, "Your name"));
        fields.add(labeledField("Customer", customerNameField, "Customer name"));
        fields.add(labeledField("Driver's license", driversLicenseField, "ID reference"));
        fields.add(labeledField("Check number", checkNumberField, "Check no."));
        tradeInfoPanel.add(fields, BorderLayout.SOUTH);
        JPanel payment = AppTheme.transparent(new BorderLayout(14, 0));
        payment.add(AppTheme.mutedLabel("Payout method"), BorderLayout.WEST);
        payment.add(paymentTypePanel, BorderLayout.CENTER);
        tradeInfoPanel.add(payment, BorderLayout.CENTER);
        panel.add(tradeInfoPanel, BorderLayout.SOUTH);

        // Middle: Input field
        JPanel inputPanel = new JPanel(new BorderLayout(10, 5));

        JLabel instructionLabel = AppTheme.mutedLabel("Enter to add  ·  F foil  ·  E etched  ·  S surge  ·  misc / MTG misc for a custom item");
        instructionLabel.setToolTipText("Examples: TDM 3, TDM 3f, PLST ARB 1. Ctrl+F or F2 searches by name.");
        inputPanel.setOpaque(false);
        cardCodeField = new JTextField();
        cardCodeField.setFont(AppTheme.FONT_BODY.deriveFont(16f));
        cardCodeField.setPreferredSize(new Dimension(260, 36));
        cardCodeField.putClientProperty("JTextField.placeholderText", "Set + collector number, e.g. TDM 3");
        cardCodeField.putClientProperty("JTextField.leadingIcon", new com.cardpricer.gui.AppIcon(com.cardpricer.gui.AppIcon.Kind.SEARCH));
        cardCodeField.setToolTipText(instructionLabel.getToolTipText());
        JButton addCardButton = AppTheme.primaryButton("Add card");
        addCardButton.setIcon(new com.cardpricer.gui.AppIcon(com.cardpricer.gui.AppIcon.Kind.PLUS, 17));
        addCardButton.addActionListener(e -> fetchPreviewAndAdd());
        inputPanel.add(addCardButton, BorderLayout.EAST);

        cardCodeField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyTyped(KeyEvent e) {
                if (e.getKeyChar() == '+') {
                    e.consume(); // prevent '+' from being inserted into the field
                    duplicateSelectedCard();
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    // Format first, then search and add
                    String input = cardCodeField.getText();
                    ParsedCode parsed = CardCodeParser.parse(input);
                    if (parsed != null) {
                        String formatted = CardCodeParser.format(parsed);
                        cardCodeField.setText(formatted);
                    }

                    // Check if we have a preview and if it matches the current input
                    if (previewCard != null && parsed != null) {
                        String currentCode = parsed.setCode + " " + parsed.collectorNumber;
                        // If preview doesn't match current input, clear it and fetch fresh
                        if (lastPreviewCode == null || !lastPreviewCode.equals(currentCode)) {
                            clearPreview();
                            fetchPreviewAndAdd();
                        } else {
                            // Preview matches, add it
                            addCard();
                        }
                    } else {
                        // No preview yet, fetch and add
                        fetchPreviewAndAdd();
                    }
                } else if ((e.getKeyCode() == KeyEvent.VK_F && e.isControlDown()) ||
                        e.getKeyCode() == KeyEvent.VK_F2) {
                    // Ctrl+F or F2 to open search dialog
                    openSearchDialog();
                } else {
                    schedulePreview();
                }
            }
        });

        inputPanel.add(cardCodeField, BorderLayout.CENTER);

        JPanel entrySection = AppTheme.transparent(new BorderLayout(0, 5));
        entrySection.add(inputPanel, BorderLayout.NORTH);
        panel.add(entrySection, BorderLayout.NORTH);

        // Bottom: Preview
        cardPreviewLabel = AppTheme.mutedLabel("Card details appear here as you type.");
        cardPreviewLabel.setFont(AppTheme.FONT_SMALL);
        cardPreviewLabel.setBorder(new EmptyBorder(0, 0, 0, 0));
        entrySection.add(cardPreviewLabel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel labeledField(String label, JTextField field, String placeholder) {
        JPanel group = AppTheme.transparent(new BorderLayout(0, 6));
        JLabel caption = AppTheme.mutedLabel(label); caption.setLabelFor(field);
        field.putClientProperty("JTextField.placeholderText", placeholder);
        group.add(caption, BorderLayout.NORTH); group.add(field, BorderLayout.CENTER);
        return group;
    }

    private JPanel createTablePanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));

        // Table with checkbox, Condition, Qty, Unit Price, Total, and Rate columns
        String[] columns = {"☑", "Code", "Card Name", "Condition", "Qty", "Unit Price", "Total", "Rate"};
        tableModel = new com.cardpricer.gui.panel.trade.TradeTableModel();
        receivedCards = tableModel.items(); cardConditions = tableModel.conditions(); rowPayouts = tableModel.payouts();

        cardTable = new com.cardpricer.gui.EmptyStateTable(tableModel, "Your next trade starts here", "Add a card code above, search by name, or paste a list.");
        AppTheme.styleTable(cardTable);
        cardTable.setFont(cardTable.getFont().deriveFont(14f));
        cardTable.getColumnModel().getColumn(0).setPreferredWidth(40);  // Checkbox
        cardTable.getColumnModel().getColumn(1).setPreferredWidth(120); // Code
        cardTable.getColumnModel().getColumn(2).setPreferredWidth(280); // Card Name
        cardTable.getColumnModel().getColumn(3).setPreferredWidth(80);  // Condition
        cardTable.getColumnModel().getColumn(4).setPreferredWidth(60);  // Qty
        cardTable.getColumnModel().getColumn(5).setPreferredWidth(100); // Unit Price
        cardTable.getColumnModel().getColumn(6).setPreferredWidth(100); // Total
        cardTable.getColumnModel().getColumn(7).setPreferredWidth(110); // Rate

        cardTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        cardTable.setToolTipText("Ctrl-click or Shift-click to highlight multiple cards. Remove selected removes highlighted or checked cards.");

        // Show card art only while hovering over the Card Name column.
        cardTable.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                int row = cardTable.rowAtPoint(e.getPoint());
                int column = cardTable.columnAtPoint(e.getPoint());
                if (row < 0 || column < 0 || cardTable.convertColumnIndexToModel(column) != 2) {
                    if (imagePopup != null) imagePopup.hide();
                    return;
                }
                int modelRow = cardTable.convertRowIndexToModel(row);
                String url = modelRow >= 0 && modelRow < receivedCards.size()
                        ? receivedCards.get(modelRow).getCard().getImageUrl() : null;
                if (url == null || url.isBlank()) {
                    if (imagePopup != null) imagePopup.hide();
                    return;
                }
                getImagePopup().show(url, e.getLocationOnScreen());
            }
            @Override
            public void mouseDragged(MouseEvent e) { if (imagePopup != null) imagePopup.hide(); }
        });
        cardTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(MouseEvent e) { if (imagePopup != null) imagePopup.hide(); }
        });

        // Enable table sorting with 3-state cycle: asc → desc → insertion order
        javax.swing.table.TableRowSorter<DefaultTableModel> sorter =
                new javax.swing.table.TableRowSorter<>(tableModel) {
            @Override
            public void toggleSortOrder(int column) {
                java.util.List<? extends javax.swing.RowSorter.SortKey> keys = getSortKeys();
                if (!keys.isEmpty()
                        && keys.get(0).getColumn() == column
                        && keys.get(0).getSortOrder() == javax.swing.SortOrder.DESCENDING) {
                    setSortKeys(null); // third click → back to insertion order
                } else {
                    super.toggleSortOrder(column); // first click asc, second click desc
                }
            }
        };
        cardTable.setRowSorter(sorter);

        // Natural sort for Code column (col 1): "TDM 2" before "TDM 11"
        sorter.setComparator(1, NATURAL_SORT_COMPARATOR);
        // Numeric price comparator for Unit Price (col 5) and Total (col 6)
        sorter.setComparator(5, PRICE_COMPARATOR);
        sorter.setComparator(6, PRICE_COMPARATOR);
        sorter.setSortable(7, false);

        // Set up condition dropdown
        JComboBox<String> conditionCombo = new JComboBox<>(CardConstants.CONDITIONS);
        DefaultCellEditor conditionEditor = new DefaultCellEditor(conditionCombo) {
            @Override
            public boolean stopCellEditing() {
                boolean result = super.stopCellEditing();
                if (result) {
                    // Auto-focus search bar after condition change
                    SwingUtilities.invokeLater(() -> cardCodeField.requestFocusInWindow());
                }
                return result;
            }
        };
        cardTable.getColumnModel().getColumn(3).setCellEditor(conditionEditor);

        // Set up Qty column editor with validation
        JTextField qtyField = new JTextField();
        qtyField.setHorizontalAlignment(JTextField.CENTER);
        DefaultCellEditor qtyEditor = new DefaultCellEditor(qtyField) {
            @Override
            public Component getTableCellEditorComponent(JTable table, Object value,
                                                         boolean isSelected, int row, int column) {
                JTextField editor = (JTextField) super.getTableCellEditorComponent(
                        table, value, isSelected, row, column);
                editor.selectAll(); // Select all text when editing
                return editor;
            }

            @Override
            public Object getCellEditorValue() {
                String value = (String) super.getCellEditorValue();
                try {
                    return Integer.parseInt(value.trim());
                } catch (NumberFormatException e) {
                    return 1;
                }
            }

            @Override
            public boolean stopCellEditing() {
                String value = (String) super.getCellEditorValue();
                try {
                    int qty = Integer.parseInt(value.trim());
                    if (qty <= 0) {
                        JOptionPane.showMessageDialog(getParentWindow(),
                                "Quantity must be a positive integer",
                                "Invalid Quantity",
                                JOptionPane.WARNING_MESSAGE);
                        return false;
                    }
                } catch (NumberFormatException e) {
                    JOptionPane.showMessageDialog(getParentWindow(),
                            "Please enter a valid positive number",
                            "Invalid Quantity",
                            JOptionPane.ERROR_MESSAGE);
                    return false;
                }
                boolean result = super.stopCellEditing();
                if (result) {
                    // Auto-focus search bar after successful edit
                    SwingUtilities.invokeLater(() -> cardCodeField.requestFocusInWindow());
                }
                return result;
            }
        };
        cardTable.getColumnModel().getColumn(4).setCellEditor(qtyEditor);

        // Set up Unit Price editor with POS-style money entry
        PosMoneyField posField = new PosMoneyField();
        DefaultCellEditor priceEditor = new DefaultCellEditor(posField) {
            @Override
            public Component getTableCellEditorComponent(JTable table, Object value,
                                                         boolean isSelected, int row, int column) {
                // Let DefaultCellEditor set the text via our DocumentFilter, then select all.
                super.getTableCellEditorComponent(table, value, isSelected, row, column);
                posField.selectAll();
                return posField;
            }

            @Override
            public Object getCellEditorValue() {
                return String.format(java.util.Locale.ROOT, "$%.2f", posField.getValue());
            }

            @Override
            public boolean stopCellEditing() {
                if (posField.getValue().compareTo(BigDecimal.ZERO) < 0) {
                    JOptionPane.showMessageDialog(getParentWindow(),
                            "Price cannot be negative",
                            "Invalid Price",
                            JOptionPane.WARNING_MESSAGE);
                    return false;
                }
                boolean result = super.stopCellEditing();
                if (result) {
                    SwingUtilities.invokeLater(() -> cardCodeField.requestFocusInWindow());
                }
                return result;
            }
        };
        cardTable.getColumnModel().getColumn(5).setCellEditor(priceEditor);

        // F1+F2: Register bounty-aware renderer on all non-checkbox columns (cols 1–7)
        BountyAwareRenderer bountyRenderer = new BountyAwareRenderer();
        for (int c = 1; c <= 7; c++) {
            cardTable.getColumnModel().getColumn(c).setCellRenderer(bountyRenderer);
        }

        // F7: Restore saved column widths from preferences
        restoreColumnWidths();

        // F7: Persist column widths whenever the user resizes a column
        cardTable.getColumnModel().addColumnModelListener(new TableColumnModelListener() {
            @Override public void columnMarginChanged(ChangeEvent e) {
                if (cardTable.getTableHeader().getResizingColumn() == null) return;
                Preferences prefs = Preferences.userNodeForPackage(PreferencesPanel.class);
                for (int i = 0; i < cardTable.getColumnCount(); i++) {
                    var column = cardTable.getColumnModel().getColumn(i);
                    prefs.putInt(COLUMN_WIDTH_PREF + column.getModelIndex(), column.getWidth());
                }
            }
            @Override public void columnAdded(TableColumnModelEvent e) {}
            @Override public void columnRemoved(TableColumnModelEvent e) {}
            @Override public void columnMoved(TableColumnModelEvent e) {}
            @Override public void columnSelectionChanged(javax.swing.event.ListSelectionEvent e) {}
        });

        tableModel.addTableModelListener(e -> {
            if (e.getColumn() == 3 || e.getColumn() == 4 || e.getColumn() == 5) refreshSummary();
            if (!isRefreshingSummary && autosaveTimer != null && e.getColumn()!=0) markDraftChanged();
        });

        // Add right-click context menu
        JPopupMenu contextMenu = new JPopupMenu();

        JMenuItem deleteItem = new JMenuItem("Delete selected cards");
        deleteItem.addActionListener(e -> removeSelectedCards());
        contextMenu.add(deleteItem);

        JMenuItem scryfallItem = new JMenuItem("Open in Scryfall");
        scryfallItem.addActionListener(e -> {
            int row = cardTable.getSelectedRow();
            if (row >= 0) {
                int modelRow = cardTable.convertRowIndexToModel(row);
                TradeItem item = receivedCards.get(modelRow);
                Card card = item.getCard();

                String scryfallUrl = String.format(java.util.Locale.ROOT,
                        "https://scryfall.com/card/%s/%s",
                        card.getSetCode().toLowerCase(),
                        card.getCollectorNumber()
                );

                openInBrowser(scryfallUrl);
            }
        });
        contextMenu.add(scryfallItem);

        cardTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showContextMenu(e);
                }
            }

            private void showContextMenu(java.awt.event.MouseEvent e) {
                int row = cardTable.rowAtPoint(e.getPoint());
                if (row >= 0) {
                    if (!cardTable.isRowSelected(row)) cardTable.setRowSelectionInterval(row, row);
                    contextMenu.show(e.getComponent(), e.getX(), e.getY());
                }
            }
        });

        JScrollPane scrollPane = new JScrollPane(cardTable);
        scrollPane.setBorder(AppTheme.cardBorder(0));
        scrollPane.setColumnHeaderView(cardTable.getTableHeader());
        JPanel tableHeading = AppTheme.transparent(new BorderLayout());
        JLabel itemsHeading = new JLabel("Trade items"); itemsHeading.setFont(AppTheme.FONT_HEADING);
        tableHeading.add(itemsHeading, BorderLayout.WEST);
        panel.add(tableHeading, BorderLayout.NORTH);

        // Add keyboard shortcuts for table
        InputMap inputMap = cardTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap actionMap = cardTable.getActionMap();

        // + key to duplicate selected card
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_PLUS, 0), "duplicateCard");
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, InputEvent.SHIFT_DOWN_MASK), "duplicateCard");
        actionMap.put("duplicateCard", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                duplicateSelectedCard();
            }
        });

        // F2 key to open search
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F2, 0), "openSearch");
        actionMap.put("openSearch", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                openSearchDialog();
            }
        });

        panel.add(scrollPane, BorderLayout.CENTER);

        // Button panel with selection controls and remove button
        JPanel buttonPanel = new JPanel(new com.cardpricer.gui.WrapLayout(FlowLayout.RIGHT, 6, 0));

        JButton selectAllBtn = AppTheme.secondaryButton("Select all");


        selectAllBtn.addActionListener(e -> selectAllCards(true));

        JButton deselectAllBtn = AppTheme.secondaryButton("Deselect all");


        deselectAllBtn.addActionListener(e -> selectAllCards(false));

        JButton removeSelectedBtn = AppTheme.dangerButton("Remove selected");
        removeSelectedBtn.setToolTipText("Remove all highlighted or checked cards");


        removeSelectedBtn.addActionListener(e -> removeSelectedCards());

        buttonPanel.add(selectAllBtn);
        buttonPanel.add(deselectAllBtn);
        buttonPanel.add(removeSelectedBtn);

        tableHeading.add(buttonPanel, BorderLayout.EAST);

        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = AppTheme.transparent(new BorderLayout(0, 8));
        panel.add(summaryPanel, BorderLayout.NORTH);
        JPanel actions = AppTheme.transparent(new BorderLayout(8, 0));
        JPanel tools = AppTheme.transparent(new com.cardpricer.gui.WrapLayout(FlowLayout.LEFT, 6, 0));
        JButton search = AppTheme.secondaryButton("Search");
        search.setIcon(new com.cardpricer.gui.AppIcon(com.cardpricer.gui.AppIcon.Kind.SEARCH, 16));
        search.setToolTipText("Search by name (Ctrl+F)"); search.addActionListener(e -> openSearchDialog());
        JButton paste = AppTheme.secondaryButton("Paste list"); paste.setToolTipText("Import cards (Ctrl+L)");
        paste.addActionListener(e -> showPasteImportDialog());
        undoBtn = AppTheme.secondaryButton("Undo"); undoBtn.setEnabled(false); undoBtn.setToolTipText("Undo (Ctrl+Z)");
        undoBtn.addActionListener(e -> undoLastCard());
        JButton vintage = AppTheme.secondaryButton("Vintage codes"); vintage.setToolTipText("Vintage reference (F4)");
        vintage.addActionListener(e -> showVintageReference());
        JButton help = AppTheme.secondaryButton(""); help.setIcon(new com.cardpricer.gui.AppIcon(com.cardpricer.gui.AppIcon.Kind.HELP, 17));
        help.setToolTipText("Keyboard shortcuts (F1)"); help.getAccessibleContext().setAccessibleName("Keyboard shortcuts");
        help.setMargin(new Insets(7, 7, 7, 7));
        help.addActionListener(e -> ShortcutHelpDialog.show(SwingUtilities.getWindowAncestor(this), HELP_TITLE, HELP_COLS, HELP_ROWS));
        JButton drafts=AppTheme.secondaryButton("Drafts");
        drafts.addActionListener(e -> {
            if(!finishCellEdit()) return;
            com.cardpricer.gui.panel.trade.TradeRecoveryPresenter.choose(this,draftId,this::performAutosave,this::resumeDraft);
        });
        JButton park=AppTheme.secondaryButton("Save for later");
        park.addActionListener(e -> {
            if(!hasUnsavedCards() || !finishCellEdit()) return;
            com.cardpricer.gui.panel.trade.TradeRecoveryPresenter.park(this,this::performAutosave,()->resetTradeState(false));
        });
        tools.add(search); tools.add(paste); tools.add(undoBtn); tools.add(vintage); tools.add(help); tools.add(drafts); tools.add(park);
        actions.add(tools, BorderLayout.CENTER);
        JPanel approve = AppTheme.transparent(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        JButton clear = AppTheme.dangerButton("Clear"); clear.setToolTipText("Clear all cards"); clear.addActionListener(e -> clearAll());
        saveExportBtn = AppTheme.primaryButton("Save & export");
        saveExportBtn.setIcon(new com.cardpricer.gui.AppIcon(com.cardpricer.gui.AppIcon.Kind.ARROW, 17));
        saveExportBtn.setHorizontalTextPosition(SwingConstants.LEFT);
        saveExportBtn.addActionListener(e -> saveAndExport());
        cancelEditButton.setVisible(false);
        cancelEditButton.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(getParentWindow(),"Discard these edits? The saved trade will stay as it was.",
                    "Cancel edit",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION) clearTradeState();
        });
        approve.add(cancelEditButton); approve.add(clear); approve.add(saveExportBtn); actions.add(approve, BorderLayout.EAST);
        editingLabel.setVisible(false);
        JPanel recoveryStatus=AppTheme.transparent(new BorderLayout(8,0));
        recoveryStatus.add(editingLabel,BorderLayout.CENTER);recoveryStatus.add(draftSaveStatus,BorderLayout.EAST);
        panel.add(recoveryStatus,BorderLayout.SOUTH);
        panel.add(actions, BorderLayout.CENTER);
        summaryPanel.update(BigDecimal.ZERO, 0, paymentTypePanel.getPaymentType(), BigDecimal.ZERO, BigDecimal.ZERO);
        return panel;
    }

    // -------------------------------------------------------------------------
    // Preview
    // -------------------------------------------------------------------------

    private Timer previewTimer;

    private void schedulePreview() {
        entryPresenter.cancel();
        if (previewTimer != null) {
            previewTimer.stop();
        }

        previewTimer = new Timer(com.cardpricer.service.CardSearchService.TYPING_DELAY_MS, e -> fetchPreview());
        previewTimer.setRepeats(false);
        previewTimer.start();
    }

    private void fetchPreview() {
        ParsedCode parsed=CardCodeParser.parse(cardCodeField.getText());
        if (parsed==null) {entryPresenter.cancel();clearPreview();return;}
        String request=CardCodeParser.format(parsed);
        entryPresenter.lookup(parsed,card -> {
            ParsedCode current=CardCodeParser.parse(cardCodeField.getText());
            if (current==null || !CardCodeParser.format(current).equals(request))return;
            lastPreviewCode=request;previewOriginalSetCode=parsed.setCode;displayPreview(card,parsed.finish);
        },failure -> clearPreview());
    }

    private void fetchPreviewAndAdd() {
        if (previewTimer != null) previewTimer.stop();
        String input=cardCodeField.getText();
        if (input==null || input.isBlank())return;
        if (input.trim().matches("(?i)(?:misc|mtg\\s+misc)")) {promptForMiscCard();return;}
        ParsedCode parsed=CardCodeParser.parse(input);
        if (parsed==null) {JOptionPane.showMessageDialog(getParentWindow(),"Use SET NUMBER, with optional F/E/S finish.");return;}
        cardPreviewLabel.setText("Loading...");
        entryPresenter.lookup(parsed,card -> {
            previewCard=card;previewFinish=parsed.finish;previewOriginalSetCode=parsed.setCode;
            if (!com.cardpricer.gui.panel.trade.TradeEntryPresenter.hasPrice(card,parsed.finish)) {promptForManualPriceOnCard(card,parsed);return;}
            displayPreview(card,parsed.finish);
            addCard();
        },failure -> {
            if (failure.getMessage()!=null && failure.getMessage().contains("not found"))promptForManualPrice(parsed);
            else {JOptionPane.showMessageDialog(getParentWindow(),"Card lookup failed: "+failure.getMessage());clearPreview();}
        });
    }

    /**
     * Shows a POS-style price entry dialog.
     * Returns the entered price, or {@code null} if the user cancelled.
     */
    private BigDecimal showPriceInputDialog(String title, String message) {
        PosMoneyField field = new PosMoneyField();
        field.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && field.isShowing()) {
                SwingUtilities.invokeLater(field::requestFocusInWindow);
            }
        });
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.add(new JLabel("<html>" + message.replace("\n", "<br>") + "</html>"), BorderLayout.NORTH);
        panel.add(field, BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(getParentWindow(), panel,
                title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        return result == JOptionPane.OK_OPTION ? field.getValue() : null;
    }

    private void promptForManualPrice(ParsedCode parsed) {
        BigDecimal price = showPriceInputDialog("Manual Price Entry",
                String.format(java.util.Locale.ROOT, "Card %s %s not found in Scryfall.\nEnter manual price:",
                        parsed.setCode, parsed.collectorNumber));

        if (price == null) {
            cardCodeField.setText("");
            clearPreview();
            cardCodeField.requestFocusInWindow();
            return;
        }

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            JOptionPane.showMessageDialog(getParentWindow(),
                    "Price must be greater than $0.00",
                    "Invalid Price",
                    JOptionPane.WARNING_MESSAGE);
            cardCodeField.setText("");
            clearPreview();
            cardCodeField.requestFocusInWindow();
            return;
        }

        String code = parsed.setCode + " " + parsed.collectorNumber;
        if (!parsed.finish.isEmpty()) {
            code += parsed.finish;
        }

        // Add to table with NM condition by default

        // Create a dummy TradeItem to keep receivedCards in sync
        Card miscCard = new Card();
        miscCard.setName("Misc Magic Card");
        miscCard.setSetCode("MISC");
        miscCard.setCollectorNumber("1");
        miscCard.setRarity("common");
        miscCard.setPrice(price.toString());

        TradeItem item = new TradeItem(miscCard, false, 1);
        tableModel.addTrade(item, "NM", price);

        refreshSummary();

        // Auto-highlight and scroll to the newly added row
        int viewRow = cardTable.convertRowIndexToView(tableModel.getRowCount() - 1);
        cardTable.setRowSelectionInterval(viewRow, viewRow);
        cardTable.scrollRectToVisible(cardTable.getCellRect(viewRow, 0, true));

        cardCodeField.setText("");
        clearPreview();
        cardCodeField.requestFocusInWindow();
    }

    /**
     * Prompts user for manual price when card exists but has no price
     */
    private void promptForManualPriceOnCard(Card card, ParsedCode parsed) {
        boolean isFoil = "F".equals(parsed.finish) || "E".equals(parsed.finish) || "S".equals(parsed.finish);
        String finishType = "E".equals(parsed.finish) ? "etched"
                : "S".equals(parsed.finish) ? "surge foil"
                : isFoil ? "foil" : "normal";

        BigDecimal price = showPriceInputDialog("Manual Price Entry",
                String.format(java.util.Locale.ROOT, "Card '%s' has no %s price listed.\nEnter manual price:",
                        card.getName(), finishType));

        if (price == null) {
            cardCodeField.setText("");
            clearPreview();
            cardCodeField.requestFocusInWindow();
            return;
        }

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            JOptionPane.showMessageDialog(getParentWindow(),
                    "Price must be greater than $0.00",
                    "Invalid Price",
                    JOptionPane.WARNING_MESSAGE);
            cardCodeField.setText("");
            clearPreview();
            cardCodeField.requestFocusInWindow();
            return;
        }

        // Keep an explicit price for the selected finish.
        if ("E".equals(parsed.finish)) { card.setEtchedPrice(price.toString()); }
        else if (isFoil) {
            card.setFoilPrice(price.toString());
        } else {
            card.setPrice(price.toString());
        }

        // Now display and add the card
        previewCard = card;
        previewFinish = parsed.finish;
        displayPreview(card, parsed.finish);
        addCard();
    }

    /**
     * Prompts user to add a misc card with custom name and price
     */
    private void promptForMiscCard() {
        // Create a dialog for misc card entry
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.fill = GridBagConstraints.HORIZONTAL;

        JTextField nameField = new JTextField(30);
        PosMoneyField priceField = new PosMoneyField();

        // Auto-focus the name field when the dialog becomes visible
        nameField.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && nameField.isShowing()) {
                SwingUtilities.invokeLater(nameField::requestFocusInWindow);
            }
        });

        // Enter in name field moves focus to price field (does not submit yet)
        nameField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    priceField.requestFocusInWindow();
                    e.consume();
                }
            }
        });

        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Card Name:"), gbc);

        gbc.gridx = 1;
        panel.add(nameField, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("Price:"), gbc);

        gbc.gridx = 1;
        panel.add(priceField, gbc);

        int result = JOptionPane.showConfirmDialog(getParentWindow(), panel,
                "Add Misc Card", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result != JOptionPane.OK_OPTION) {
            cardCodeField.setText("");
            clearPreview();
            cardCodeField.requestFocusInWindow();
            return;
        }

        String cardName = nameField.getText().trim();
        BigDecimal price = priceField.getValue();

        if (cardName.isEmpty()) {
            cardName = "Misc Magic Card";
        }

        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            JOptionPane.showMessageDialog(getParentWindow(),
                    "Price must be greater than $0.00",
                    "Invalid Price",
                    JOptionPane.WARNING_MESSAGE);
            cardCodeField.setText("");
            clearPreview();
            cardCodeField.requestFocusInWindow();
            return;
        }

        // Add to table with NM condition by default

        // Create a dummy TradeItem to keep receivedCards in sync
        Card miscCard = new Card();
        miscCard.setName(cardName);
        miscCard.setSetCode("MISC");
        miscCard.setCollectorNumber("1");
        miscCard.setRarity("common");
        miscCard.setPrice(price.toString());

        TradeItem item = new TradeItem(miscCard, false, 1);
        tableModel.addTrade(item, "NM", price);

        refreshSummary();

        // Auto-highlight and scroll to the newly added row
        int viewRow = cardTable.convertRowIndexToView(tableModel.getRowCount() - 1);
        cardTable.setRowSelectionInterval(viewRow, viewRow);
        cardTable.scrollRectToVisible(cardTable.getCellRect(viewRow, 0, true));

        cardCodeField.setText("");
        clearPreview();
        cardCodeField.requestFocusInWindow();
    }

    private void displayPreview(Card card, String finish) {
        previewCard = card;
        previewFinish = finish;

        StringBuilder text = new StringBuilder();
        text.append("✓ ").append(card.getName());

        BigDecimal price;
        String finishName;

        if ("F".equals(finish)) {
            price = card.getFoilPriceAsBigDecimal();
            finishName = "Foil";
        } else if ("E".equals(finish)) {
            price = card.getEtchedPriceAsBigDecimal();
            finishName = "Etched";
        } else if ("S".equals(finish)) {
            price = card.getFoilPriceAsBigDecimal();
            finishName = "Surge Foil";
        } else {
            price = card.getPriceAsBigDecimal();
            finishName = "Normal";
        }

        BigDecimal roundedPrice = pricingService.applyPricingRules(price, card.getRarity());

        text.append(String.format(java.util.Locale.ROOT, " (%s) - $%.2f [%s %s]",
                finishName, roundedPrice, card.getSetCode(), CardCodeParser.capitalize(card.getRarity())));

        cardPreviewLabel.setText(text.toString());
        cardPreviewLabel.setForeground(price.compareTo(BigDecimal.ZERO) > 0 ?
                new Color(0, 120, 0) : Color.RED);
    }

    private void clearPreview() {
        previewCard = null;
        previewFinish = null;
        lastPreviewCode = null;
        previewOriginalSetCode = "";
        cardPreviewLabel.setText("Enter a card code above...");
        cardPreviewLabel.setForeground(UIManager.getColor("Label.foreground"));
    }

    // -------------------------------------------------------------------------
    // Card management
    // -------------------------------------------------------------------------

    private void addCard() {
        if (previewCard == null) {
            JOptionPane.showMessageDialog(getParentWindow(),
                    "Please enter a valid card code",
                    "No Card",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        boolean isFoil = "F".equals(previewFinish) || "E".equals(previewFinish) || "S".equals(previewFinish);

        // Check if price is available, if not prompt for manual entry
        if (("F".equals(previewFinish) || "S".equals(previewFinish)) && !previewCard.hasFoilPrice()) {
            String finishLabel = "S".equals(previewFinish) ? "surge foil" : "foil";
            BigDecimal price = showPriceInputDialog("Manual Price Entry",
                    String.format(java.util.Locale.ROOT, "Card '%s' has no %s price available.\nEnter manual price:",
                            previewCard.getName(), finishLabel));
            if (price == null) return;
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                JOptionPane.showMessageDialog(getParentWindow(),
                        "Price must be greater than $0.00", "Invalid Price", JOptionPane.WARNING_MESSAGE);
                return;
            }
            previewCard.setFoilPrice(price.toString());
        } else if ("E".equals(previewFinish) && !previewCard.hasEtchedPrice()) {
            BigDecimal price = showPriceInputDialog("Manual Price Entry",
                    String.format(java.util.Locale.ROOT, "Card '%s' has no etched price available.\nEnter manual price:",
                            previewCard.getName()));
            if (price == null) return;
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                JOptionPane.showMessageDialog(getParentWindow(),
                        "Price must be greater than $0.00", "Invalid Price", JOptionPane.WARNING_MESSAGE);
                return;
            }
            previewCard.setEtchedPrice(price.toString());
        }

        if (!isFoil && !previewCard.hasNormalPrice()) {
            BigDecimal price = showPriceInputDialog("Manual Price Entry",
                    String.format(java.util.Locale.ROOT, "Card '%s' has no normal price available.\nEnter manual price:",
                            previewCard.getName()));
            if (price == null) return;
            if (price.compareTo(BigDecimal.ZERO) <= 0) {
                JOptionPane.showMessageDialog(getParentWindow(),
                        "Price must be greater than $0.00", "Invalid Price", JOptionPane.WARNING_MESSAGE);
                return;
            }
            previewCard.setPrice(price.toString());
        }

        TradeItem item = new TradeItem(previewCard, isFoil, 1, previewFinish);

        Card card = item.getCard();
        // For PLST cards, display "PLST ARB 1" in the table; saves use the underlying "ARB 1"
        String baseCode = "plst".equalsIgnoreCase(previewOriginalSetCode)
                ? "PLST " + card.getSetCode() + " " + card.getCollectorNumber()
                : card.getSetCode() + " " + card.getCollectorNumber();

        String code = baseCode;
        if (isFoil) {
            if ("E".equals(previewFinish)) {
                code += "e";
            } else if ("S".equals(previewFinish)) {
                code += "s";
            } else {
                code += "f";
            }
        }

        StringBuilder name = new StringBuilder(card.getName());
        if (isFoil) {
            String finishLabel = "E".equals(previewFinish) ? "Etched"
                    : "S".equals(previewFinish) ? "Surge Foil" : "Foil";
            name.append(" (").append(finishLabel).append(")");
        }

        BigDecimal roundedPrice = pricingService.applyPricingRules(item.getUnitPrice(), card.getRarity());

        // Feature 7: high-value confirmation — pause and verify before adding
        if (roundedPrice.compareTo(VintageUtil.HIGH_VALUE_THRESHOLD) >= 0) {
            if (!confirmHighValueAdd(card, roundedPrice)) {
                return;
            }
        }


        tableModel.addTrade(item, "NM", roundedPrice);
        refreshSummary();

        // Track undo state for the card just added
        lastAddedItem = item;
        lastAddedRow  = tableModel.getRowCount() - 1;
        if (undoBtn != null) undoBtn.setEnabled(true);

        // Auto-highlight and scroll to the newly added row
        int viewRow = cardTable.convertRowIndexToView(tableModel.getRowCount() - 1);
        cardTable.setRowSelectionInterval(viewRow, viewRow);
        cardTable.scrollRectToVisible(cardTable.getCellRect(viewRow, 0, true));

        cardCodeField.setText("");
        clearPreview();
        cardCodeField.requestFocusInWindow();
    }

    // -------------------------------------------------------------------------
    // Programmatic card additions (PriceCheckDialog / PasteImportDialog)
    // -------------------------------------------------------------------------

    /**
     * Adds a pre-fetched card to the trade table.
     * Used by {@link PriceCheckDialog} and {@link PasteImportDialog}; skips
     * high-value prompts, undo tracking, and code-field clearing since those
     * belong to the manual-entry flow.
     *
     * <p>Silently skips if the chosen finish has no price available.
     *
     * @param card            fetched card data
     * @param finishType      finish code: {@code ""} normal, {@code "F"} foil,
     *                        {@code "E"} etched, {@code "S"} surge foil
     * @param originalSetCode original set code as entered (e.g. {@code "plst"} for
     *                        PLST display, or {@code card.getSetCode()} otherwise)
     */
    public void addFetchedCard(Card card, String finishType, String originalSetCode) {
        boolean isFoil = !finishType.isEmpty();

        // Skip silently if the chosen finish has no price
        boolean hasPrice;
        if ("F".equals(finishType) || "S".equals(finishType)) {
            hasPrice = card.hasFoilPrice();
        } else if ("E".equals(finishType)) {
            hasPrice = card.hasEtchedPrice();
        } else {
            hasPrice = card.hasNormalPrice();
        }
        if (!hasPrice) return;

        TradeItem item = new TradeItem(card, isFoil, 1, finishType);

        String baseCode = "plst".equalsIgnoreCase(originalSetCode)
                ? "PLST " + card.getSetCode() + " " + card.getCollectorNumber()
                : card.getSetCode() + " " + card.getCollectorNumber();

        String code = baseCode;
        if (isFoil) {
            if ("E".equals(finishType)) {
                code += "e";
            } else if ("S".equals(finishType)) {
                code += "s";
            } else {
                code += "f";
            }
        }

        StringBuilder name = new StringBuilder(card.getName());
        if (isFoil) {
            String finishLabel = "E".equals(finishType) ? "Etched"
                    : "S".equals(finishType) ? "Surge Foil" : "Foil";
            name.append(" (").append(finishLabel).append(")");
        }

        BigDecimal roundedPrice = pricingService.applyPricingRules(item.getUnitPrice(), card.getRarity());

        tableModel.addTrade(item, "NM", roundedPrice);

        refreshSummary();

        // Scroll to and select the newly added row
        int viewRow = cardTable.convertRowIndexToView(tableModel.getRowCount() - 1);
        cardTable.setRowSelectionInterval(viewRow, viewRow);
        cardTable.scrollRectToVisible(cardTable.getCellRect(viewRow, 0, true));
    }

    private void showPasteImportDialog() {
        final long generation=draftGeneration;
        PasteImportDialog dlg=new PasteImportDialog(SwingUtilities.getWindowAncestor(this),apiService,results -> {
            if (generation!=draftGeneration) return;
            batchingCards=true;
            try { for (var result:results) if (result.ok()) addFetchedCard(result.card(),result.parsed().finish,result.parsed().setCode); }
            finally { batchingCards=false; refreshSummary(); }
        });
        dlg.setVisible(true);
    }

    // -------------------------------------------------------------------------

    /** Removes the most recently added card. Single-level undo. */
    private void undoLastCard() {
        if (lastAddedItem == null) {
            JOptionPane.showMessageDialog(getParentWindow(),
                    "Nothing to undo.", "Undo", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int row = tableModel.indexOf(lastAddedItem.getLineId());
        if (row >= 0 && row < tableModel.getRowCount()) {
            tableModel.removeRow(row);
        }
        clearUndoState();
        refreshSummary();
    }

    private void clearUndoState() {
        lastAddedItem = null;
        lastAddedRow  = -1;
        if (undoBtn != null) undoBtn.setEnabled(false);
    }

    /**
     * Duplicates the selected card (adds another copy with same attributes)
     */
    private void duplicateSelectedCard() {
        int row = cardTable.getSelectedRow();
        if (row < 0) {
            return; // No selection
        }

        // Convert view row to model row since table might be sorted
        int modelRow = cardTable.convertRowIndexToModel(row);

        // Get existing card data
        TradeItem existingItem = receivedCards.get(modelRow);
        String condition = (String) tableModel.getValueAt(modelRow, 3);
        Object qtyObj = tableModel.getValueAt(modelRow, 4);
        int qty = (qtyObj instanceof Integer) ? (Integer) qtyObj : Integer.parseInt(qtyObj.toString());
        String unitPrice = (String) tableModel.getValueAt(modelRow, 5);
        String code = (String) tableModel.getValueAt(modelRow, 1);
        String name = (String) tableModel.getValueAt(modelRow, 2);

        // Calculate total
        BigDecimal price = tableModel.priceAt(modelRow);
        BigDecimal total = price.multiply(BigDecimal.valueOf(qty));

        // Create new TradeItem copy
        TradeItem newItem = new TradeItem(existingItem.getCard(), existingItem.isFoil(), qty, existingItem.getFinishType());
        newItem.setQuantity(qty);
        newItem.setUnitPrice(existingItem.getUnitPrice());
        newItem.setManualOverride(existingItem.getManualCondition(),existingItem.getManualPrice());

        // Add to lists

        // Add to table
        tableModel.addTrade(newItem, condition, price);

        refreshSummary();

        // Select the newly added duplicate
        int viewRow = cardTable.convertRowIndexToView(tableModel.getRowCount() - 1);
        cardTable.setRowSelectionInterval(viewRow, viewRow);
        cardTable.scrollRectToVisible(cardTable.getCellRect(viewRow, 0, true));
    }

    /**
     * Selects or deselects all checkboxes
     */
    private void selectAllCards(boolean selected) {
        if (cardTable.isEditing()) cardTable.getCellEditor().cancelCellEditing();
        if (selected) cardTable.selectAll();else cardTable.clearSelection();
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            tableModel.setValueAt(selected, i, 0); // Column 0 is checkbox
        }
    }

    /**
     * Removes all highlighted or checked cards, using model indices even when sorted.
     */
    private void removeSelectedCards() {
        if (cardTable.isEditing() && !cardTable.getCellEditor().stopCellEditing()) return;
        List<Integer> rowsToDelete = selectedCardRows();

        if (rowsToDelete.isEmpty()) {
            JOptionPane.showMessageDialog(getParentWindow(),
                    "No cards selected for deletion",
                    "Nothing to Delete",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        int confirm = JOptionPane.showConfirmDialog(getParentWindow(),
                String.format(java.util.Locale.ROOT, "Delete %d selected card(s)?", rowsToDelete.size()),
                "Confirm Delete",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) removeCardRows(rowsToDelete);
    }

    private List<Integer> selectedCardRows() {
        java.util.SortedSet<Integer> rows = new java.util.TreeSet<>();
        for (int viewRow : cardTable.getSelectedRows()) rows.add(cardTable.convertRowIndexToModel(viewRow));
        for (int i = 0; i < tableModel.getRowCount(); i++) {
            if (Boolean.TRUE.equals(tableModel.getValueAt(i, 0))) rows.add(i);
        }
        return new ArrayList<>(rows);
    }

    private void removeCardRows(List<Integer> rowsToDelete) {
        for (int i = rowsToDelete.size() - 1; i >= 0; i--) {
            tableModel.removeRow(rowsToDelete.get(i));
        }
        clearUndoState();
        refreshSummary();
    }

    /**
     * Updates the price for a specific row based on its condition
     */
    private void updatePriceForCondition(int row) {
        // Convert view row to model row since table might be sorted
        int modelRow = cardTable.convertRowIndexToModel(row);

        if (modelRow < 0 || modelRow >= receivedCards.size()) {
            return;
        }

        String condition = (String) tableModel.getValueAt(modelRow, 3); // Column 3 is Condition
        cardConditions.set(modelRow, condition);

        TradeItem item = receivedCards.get(modelRow);
        Card card = item.getCard();

        // Get base price (already rounded by pricing rules)
        BigDecimal basePrice = pricingService.applyPricingRules(item.getUnitPrice(), card.getRarity());

        // Apply condition multiplier
        BigDecimal conditionPrice = pricingService.applyConditionMultiplier(basePrice, condition);

        // Update the unit price in the table (Column 5 is Unit Price)
        tableModel.setValueAt(String.format(java.util.Locale.ROOT, "$%.2f", conditionPrice), modelRow, 5);

        // Recalculate and update total
        updateRowTotal(modelRow);
    }

    // -------------------------------------------------------------------------
    // Summary
    // -------------------------------------------------------------------------

    /** Recomputes totals from the table and pushes updates to sub-panels. */
    private void refreshSummary() {
        if (isRefreshingSummary || batchingCards) return;
        isRefreshingSummary = true;
        try {
            refreshSummaryImpl();
        } finally {
            isRefreshingSummary = false;
        }
    }

    private void refreshSummaryImpl() {
        int gen=BuyRateService.getSaveGeneration();
        if(gen!=lastKnownBuyRateGen) {
            lastKnownBuyRateGen=gen;
            buyRateService.refreshFromPublished();
            tableModel.invalidatePayouts();
        }
        var totals=tableModel.summary(this::payoutFor);
        paymentTypePanel.setTotal(totals.market(),totals.credit(),totals.check());
        summaryPanel.update(totals.market(),totals.quantity(),paymentTypePanel.getPaymentType(),totals.credit(),totals.check());
        if(cardTable!=null) cardTable.repaint();
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    private void clearAll() {
        if (receivedCards.isEmpty()) {
            return;
        }

        int result = JOptionPane.showConfirmDialog(getParentWindow(),
                "Clear all " + receivedCards.size() + " cards?",
                "Confirm Clear",
                JOptionPane.YES_NO_OPTION);

        if (result == JOptionPane.YES_OPTION) {
            draftGeneration++; entryPresenter.cancel();
            if (editingRevision==null) { TradeSessionService.discard(draftId); draftId=java.util.UUID.randomUUID(); }
            tableModel.setRowCount(0);
            clearUndoState();
            refreshSummary();
            cardCodeField.requestFocusInWindow();
        }
    }

    /** Clears all trade state without prompting. Called after a successful save/export. */
    private void clearTradeState() { resetTradeState(true); }

    private void resetTradeState(boolean discard) {
        if(discard) TradeSessionService.discard(draftId);
        draftGeneration++; entryPresenter.cancel();
        draftId = java.util.UUID.randomUUID();
        draftRevision = 0;
        editingRevision = null;
        savedLines.clear();
        editingLabel.setVisible(false);
        cancelEditButton.setVisible(false);
        paymentTypePanel.restore("credit",BigDecimal.ZERO,BigDecimal.ZERO);
        saveExportBtn.setText("Save & export");
        resizeEntryArea();
        tableModel.setRowCount(0);
        clearUndoState();
        traderNameField.setText("");
        customerNameField.setText("");
        driversLicenseField.setText("");
        checkNumberField.setText("");
        draftSaveStatus.setText("No active draft");
        refreshSummary();
        cardCodeField.requestFocusInWindow();
    }

    private boolean exportToPOS() {
        saveAndExport();
        return false;
    }

    /**
     * Combined action for all payment types:
     * <ol>
     *   <li>Shows a trade-summary confirmation dialog.</li>
     *   <li>Inventory — exports a single CSV via {@link #addToInventory()} then clears.</li>
     *   <li>Other — saves TXT receipt via {@link #saveList()}, exports POS CSV via
     *       {@link #exportToPOS()}, then clears if both succeed.</li>
     * </ol>
     */
    private void saveAndExport() {
        if (receivedCards.isEmpty()) return;
        final com.cardpricer.model.TradeDraft draft;
        try {
            if (cardTable.isEditing() && !cardTable.getCellEditor().stopCellEditing()) return;
            draft = snapshotDraft();
            draft.validateForApproval();
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(getParentWindow(), e.getMessage(), "Invalid trade", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!confirmProceedWithoutNames()) return;
        boolean correction=editingRevision!=null;
        autosaveTimer.stop();
        try {
        new com.cardpricer.gui.panel.trade.TradeFinalizationPresenter().approve(getParentWindow(),draft,ledgerPath(),
                com.cardpricer.util.AppDataDirectory.trades().toPath(),editingRevision,pending -> {
            lastSavedTxtPath=com.cardpricer.util.AppDataDirectory.trades().toPath().resolve(
                    com.cardpricer.service.TradeApplicationService.outputPrefix(draft,correction)+".txt").toString();
            clearTradeState();TradeReceivingExportService.syncMissingToSharedFolder();
            JOptionPane.showMessageDialog(getParentWindow(),pending==0 ? "Trade saved. Output files are ready."
                    : "Trade saved; export pending. Use Retry exports without another payment.");
        });
        } finally { if(hasUnsavedCards()) autosaveTimer.restart(); }
    }

    private java.nio.file.Path ledgerPath() {
        return com.cardpricer.util.AppDataDirectory.root().toPath().resolve("ledger/trades.sqlite");
    }

    private java.util.function.Supplier<com.cardpricer.model.TradeDraft> captureDraft() {
        refreshSummary();
        return new com.cardpricer.gui.panel.trade.TradeCustomerPresenter(traderNameField,customerNameField,
                driversLicenseField,checkNumberField,paymentTypePanel).captureDeferred(draftId,++draftRevision,
                tableModel.captureLines(savedLines),buyRateService.getRevision());
    }

    private com.cardpricer.model.TradeDraft snapshotDraft() { return captureDraft().get(); }

    private BuyRateService.PayoutResult payoutFor(TradeItem item, BigDecimal value) {
        var saved=savedLines.get(item.getLineId());
        if (saved!=null) return new BuyRateService.PayoutResult(
                value.multiply(saved.creditRate()).setScale(2,RoundingMode.HALF_UP),
                value.multiply(saved.checkRate()).setScale(2,RoundingMode.HALF_UP),saved.creditRate(),saved.checkRate(),false);
        Card card=item.getCard();
        return buyRateService.computePayout(card.getSetCode(),card.getCollectorNumber(),card.getName(),value);
    }

    /** Reuse the complete trade editor, including card lookup, condition, quantity and payment controls. */
    public boolean editSavedTrade(com.cardpricer.model.TradeDraft draft) {
        if(!finishCellEdit()) return false;
        if(hasUnsavedCards() && draft.id().equals(draftId)) return true;
        if(hasUnsavedCards()) { performAutosave(); TradeSessionService.flush(); }
        var recovery=TradeSessionService.find(draft.id());
        if(recovery!=null) { resumeDraft(recovery);return true; }
        resetTradeState(false);
        editingRevision=draft.revision();
        restoreDraft(draft);
        showEditingState();
        performAutosave();
        return true;
    }

    private void showEditingState() {
        editingLabel.setText("Editing saved trade for " + customerNameField.getText()
                + " | Revision " + editingRevision + " | Review POS inventory after saving");
        editingLabel.setToolTipText("Earlier revisions are retained. Shared saves check for changes from other workstations before accepting your correction.");
        editingLabel.setVisible(true);
        cancelEditButton.setVisible(true);
        saveExportBtn.setText("Save changes");
    }

    private void retryPendingExports() {
        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                return new com.cardpricer.service.TradeRepository(ledgerPath())
                        .retryOutputs(com.cardpricer.util.AppDataDirectory.trades().toPath());
            }
            @Override protected void done() {
                try {
                    int pending = get();
                    TradeReceivingExportService.syncMissingToSharedFolder(true);
                    syncStatusLabel.setText("Rates: "+buyRateService.getSyncStatus()+"; trades: "+TradeReceivingExportService.getSharedSyncStatus());
                    JOptionPane.showMessageDialog(getParentWindow(), pending == 0 ? "Local exports are up to date. Shared copies retry in the background; see sync status." : "Some local exports are still pending. Check the destination and retry.");
                }
                catch (Exception e) { JOptionPane.showMessageDialog(getParentWindow(), "Export retry failed: " + e.getMessage()); }
            }
        }.execute();
    }

    private boolean saveList() {
        saveAndExport();
        return false;
    }

    private BigDecimal getTotalValue() {
        return calculateTotalValue(receivedCards);
    }

    /**
     * Calculates total value for a list of trade items
     */
    private BigDecimal calculateTotalValue(List<TradeItem> items) {
        BigDecimal total=BigDecimal.ZERO;
        for (TradeItem item:items) { int row=tableModel.indexOf(item.getLineId()); if (row>=0) total=total.add(tableModel.totalAt(row)); }
        return total;
    }

    private void openSearchDialog() {
        CardSearchDialog dialog = new CardSearchDialog(
                SwingUtilities.getWindowAncestor(this),
                apiService
        );
        dialog.setVisible(true);

        Card selectedCard = dialog.getSelectedCard();
        String selectedFinish = dialog.getSelectedFinish();

        if (selectedCard != null) {
            previewCard = selectedCard;
            previewFinish = selectedFinish;
            displayPreview(selectedCard, selectedFinish);

            String code = selectedCard.getSetCode() + " " + selectedCard.getCollectorNumber();
            if (selectedFinish != null && !selectedFinish.isEmpty()) {
                code += selectedFinish;
            }
            cardCodeField.setText(code);

            cardCodeField.requestFocusInWindow();
        }
    }

    /**
     * Updates the total for a specific row based on quantity and unit price
     */
    private void updateRowTotal(int row) {
        if (row < 0 || row >= tableModel.getRowCount()) {
            return;
        }

        int qty = (Integer) tableModel.getValueAt(row, 4);
        String unitPriceStr = (String) tableModel.getValueAt(row, 5);
        BigDecimal unitPrice = new BigDecimal(unitPriceStr.replace("$", "").trim());
        BigDecimal total = unitPrice.multiply(BigDecimal.valueOf(qty));
        tableModel.setValueAt(String.format(java.util.Locale.ROOT, "$%.2f", total), row, 6);
    }

    /**
     * Opens a URL in the system's default browser
     */
    private void openInBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new java.net.URI(url));
            } else {
                // Fallback for systems without Desktop support
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    Runtime.getRuntime().exec("rundll32 url.dll,FileProtocolHandler " + url);
                } else if (os.contains("mac")) {
                    Runtime.getRuntime().exec("open " + url);
                } else if (os.contains("nix") || os.contains("nux")) {
                    Runtime.getRuntime().exec("xdg-open " + url);
                }
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(getParentWindow(),
                    "Could not open browser.\n\nURL: " + url + "\n\nPlease copy and paste this URL into your browser.",
                    "Browser Error",
                    JOptionPane.INFORMATION_MESSAGE);
        }
    }

    /**
     * Gets the parent window for centering dialogs
     */
    private Window getParentWindow() {
        return SwingUtilities.getWindowAncestor(this);
    }

    /** Opens the vintage set reference dialog via the shared ShortcutHelpDialog infrastructure. */
    private void showVintageReference() {
        ShortcutHelpDialog.show(getParentWindow(),
                "Vintage Set Reference  —  Codes & Name Shortcuts",
                VintageUtil.REF_COLUMNS, VintageUtil.REF_ROWS);
    }

    /**
     * Shows a modal confirmation dialog for high-value cards.
     * Loads the card image asynchronously inside the dialog while the user reviews.
     *
     * @param card  the card about to be added
     * @param price the computed rounded price
     * @return {@code true} if the user confirmed; {@code false} to cancel
     */
    private boolean confirmHighValueAdd(Card card, BigDecimal price) {
        if (imagePopup != null) imagePopup.hide();
        JDialog dialog = new JDialog(getParentWindow(),
                "Verify High-Value Card", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JPanel main = new JPanel(new BorderLayout(14, 10));
        main.setBorder(new EmptyBorder(16, 16, 12, 16));

        // ── Left: card image ──────────────────────────────────────────────────
        int imgW = 200;
        int imgH = (int) (imgW * 1.396);
        JLabel imgLabel = new JLabel("<html><center style='color:gray;'>Loading…</center></html>",
                SwingConstants.CENTER);
        imgLabel.setPreferredSize(new Dimension(imgW, imgH));
        imgLabel.setBorder(BorderFactory.createLineBorder(new Color(80, 80, 80)));

        if (card.getImageUrl() != null) {
            final String imgUrl = card.getImageUrl();
            SwingWorker<ImageIcon, Void> verificationImage = new SwingWorker<>() {
                @Override protected ImageIcon doInBackground() throws Exception {
                    java.awt.image.BufferedImage raw = com.cardpricer.service.CardImageLoader.read(imgUrl);
                    if (raw == null) return null;
                    int h = raw.getHeight() * imgW / raw.getWidth();
                    return new ImageIcon(raw.getScaledInstance(imgW, h, java.awt.Image.SCALE_SMOOTH));
                }
                @Override protected void done() {
                    if (isCancelled() || !dialog.isDisplayable()) return;
                    try {
                        ImageIcon icon = get();
                        if (icon != null) {
                            imgLabel.setIcon(icon);
                            imgLabel.setText(null);
                            imgLabel.setPreferredSize(null);
                            dialog.pack();
                        }
                    } catch (Exception ignored) {}
                }
            };
            dialog.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosed(java.awt.event.WindowEvent event) { verificationImage.cancel(true); }
            });
            com.cardpricer.service.TaskCoordinator.execute(verificationImage);
        }

        // ── Centre: card info ─────────────────────────────────────────────────
        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.setBorder(new EmptyBorder(0, 10, 0, 0));

        JLabel warnLabel = new JLabel("High-Value Card — Please Verify");
        warnLabel.setFont(warnLabel.getFont().deriveFont(Font.BOLD, 13f));
        warnLabel.setForeground(new Color(180, 100, 0));

        JLabel nameLabel = new JLabel(card.getName());
        nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 18f));

        String setInfo = card.getSetCode() + "  #" + card.getCollectorNumber();
        if (card.isReserved()) setInfo += "  [Reserved List]";
        JLabel setLabel = new JLabel(setInfo);
        setLabel.setForeground(UIManager.getColor("Label.disabledForeground"));

        JLabel priceLabel = new JLabel(String.format(java.util.Locale.ROOT, "Market Value:  $%.2f", price));
        priceLabel.setFont(priceLabel.getFont().deriveFont(Font.BOLD, 22f));
        priceLabel.setForeground(new Color(0, 140, 0));

        JLabel promptLabel = new JLabel(
                "<html>Physically verify the card before adding it to this trade.</html>");
        promptLabel.setFont(promptLabel.getFont().deriveFont(Font.PLAIN, 12f));

        info.add(warnLabel);
        info.add(Box.createVerticalStrut(10));
        info.add(nameLabel);
        info.add(Box.createVerticalStrut(4));
        info.add(setLabel);
        info.add(Box.createVerticalStrut(14));
        info.add(priceLabel);
        info.add(Box.createVerticalStrut(14));
        info.add(promptLabel);

        // ── Bottom: buttons ───────────────────────────────────────────────────
        boolean[] confirmed = {false};

        JButton addBtn = new JButton("Add to Trade");

        addBtn.putClientProperty("JButton.buttonType", "roundRect");
        addBtn.addActionListener(e -> { confirmed[0] = true; dialog.dispose(); });

        JButton cancelBtn = new JButton("Cancel");

        cancelBtn.putClientProperty("JButton.buttonType", "roundRect");
        cancelBtn.addActionListener(e -> dialog.dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(cancelBtn);
        buttons.add(addBtn);

        main.add(imgLabel,  BorderLayout.WEST);
        main.add(info,      BorderLayout.CENTER);
        main.add(buttons,   BorderLayout.SOUTH);

        dialog.setContentPane(main);
        dialog.getRootPane().setDefaultButton(addBtn);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowOpened(java.awt.event.WindowEvent event) {
                addBtn.requestFocusInWindow();
            }
        });
        dialog.pack();
        dialog.setMinimumSize(new Dimension(500, 300));
        dialog.setLocationRelativeTo(getParentWindow());
        dialog.setVisible(true); // blocks until closed (modal)

        return confirmed[0];
    }

    private CardImagePopup getImagePopup() {
        if (imagePopup == null) {
            imagePopup = new CardImagePopup(SwingUtilities.getWindowAncestor(this));
        }
        return imagePopup;
    }

    // -------------------------------------------------------------------------
    // Feature: Warn on missing customer/trader name
    // -------------------------------------------------------------------------

    /**
     * Checks whether customer and trader name fields are filled.
     * If either is blank, shows a YES/NO warning dialog.
     * Choosing NO highlights the empty field(s) with an orange border and returns false.
     *
     * @return {@code true} if the caller should proceed; {@code false} to abort.
     */
    private boolean confirmProceedWithoutNames() {
        List<String> missing = new ArrayList<>();
        if (customerNameField.getText().isBlank()) missing.add("Customer Name");
        if (traderNameField.getText().isBlank())   missing.add("Trader Name");
        if (missing.isEmpty()) return true;

        int result = JOptionPane.showConfirmDialog(this,
                "The following fields are empty: " + String.join(", ", missing)
                + "\n\nProceed anyway?",
                "Missing Information",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (result == JOptionPane.YES_OPTION) return true;

        // Highlight the empty fields so the user knows what to fill in
        if (customerNameField.getText().isBlank()) highlightEmptyField(customerNameField);
        if (traderNameField.getText().isBlank())   highlightEmptyField(traderNameField);
        return false;
    }

    /** Draws an orange border on {@code field} and auto-removes it when the user types. */
    private void highlightEmptyField(JTextField field) {
        javax.swing.border.Border original = field.getBorder();
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(255, 140, 0), 2),
                new EmptyBorder(2, 4, 2, 4)));
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { field.setBorder(original); }
            @Override public void removeUpdate(DocumentEvent e)  { field.setBorder(original); }
            @Override public void changedUpdate(DocumentEvent e) {}
        });
    }

    // -------------------------------------------------------------------------
    // F10: Unsaved-trade check for window-close confirmation
    // -------------------------------------------------------------------------

    /** Returns {@code true} if there are cards in the table that have not been saved. */
    public void flushDraftOnClose() {
        if(!finishCellEdit()) throw new IllegalStateException("Finish editing the current cell before closing.");
        performAutosave(); TradeSessionService.flush();
    }
    public void disposeResources() { if (previewTimer!=null) previewTimer.stop(); autosaveTimer.stop(); syncTimer.stop(); draftGeneration++; entryPresenter.cancel(); previewGeneration++; if (imagePopup!=null) imagePopup.dispose(); }

    public boolean hasUnsavedCards() {
        return editingRevision!=null || !receivedCards.isEmpty();
    }

    // -------------------------------------------------------------------------
    // F7: Column width memory
    // -------------------------------------------------------------------------

    /** Restores only widths saved by manually resizing a column. */
    private void restoreColumnWidths() {
        Preferences prefs = Preferences.userNodeForPackage(PreferencesPanel.class);
        for (int i = 0; i < cardTable.getColumnCount(); i++) {
            int w = prefs.getInt(COLUMN_WIDTH_PREF + cardTable.getColumnModel().getColumn(i).getModelIndex(), -1);
            if (w > 0) cardTable.getColumnModel().getColumn(i).setPreferredWidth(w);
        }
    }

    // -------------------------------------------------------------------------
    // F5: Autosave / crash recovery
    // -------------------------------------------------------------------------

    /** Offers to restore a previously crashed session (called on first EDT tick). */
    private void offerSessionRestore() {
        com.cardpricer.gui.panel.trade.TradeRecoveryPresenter.offer(this,this::resumeDraft,()->!hasUnsavedCards());
        if (!TradeSessionService.hasAutosave()) return;
        int choice = JOptionPane.showConfirmDialog(getParentWindow(),
                "An unsaved trade session was found.\nWould you like to restore it?",
                "Restore Session",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.QUESTION_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            TradeSessionService.SavedSession session = TradeSessionService.load();
            if (session != null) restoreSession(session);
        } else {
            TradeSessionService.clearLegacyAutosave();
        }
    }

    private void restoreDraft(com.cardpricer.model.TradeDraft draft) {
        draftId = draft.id(); draftRevision = draft.revision();
        savedLines.clear();
        if (editingRevision!=null) for (var line:draft.lines()) savedLines.put(line.id(),line);
        traderNameField.setText(draft.trader()); customerNameField.setText(draft.customer());
        driversLicenseField.setText(draft.identification()); checkNumberField.setText(draft.checkNumber());
        paymentTypePanel.restore(draft.payment(), draft.credit(), draft.check());
        resizeEntryArea();
        for (var line : draft.lines()) {
            tableModel.addTrade(line.item(), line.condition().name(), line.valuation());
        }
        refreshSummary();
    }

    /** Restores a previously saved session into the trade table. */
    private void restoreSession(TradeSessionService.SavedSession session) {
        if (session == null) return;
        JOptionPane.showMessageDialog(getParentWindow(),"Legacy recovery does not contain original printing and NM base values. Re-enter these lines before finalizing. The original recovery file is retained.");
        if (session.traderName() != null) traderNameField.setText(session.traderName());
        if (session.customerName() != null) customerNameField.setText(session.customerName());

        for (TradeSessionService.SessionRow row : session.rows()) {
            String[] codeParts = row.code().split(" ", 2);
            String setCode = codeParts.length > 0 ? codeParts[0] : "MISC";
            String collNum = codeParts.length > 1 ? codeParts[1] : "1";

            Card stub = new Card();
            stub.setName(row.cardName());
            stub.setSetCode(setCode);
            stub.setCollectorNumber(collNum);
            stub.setRarity("unknown");
            stub.setProviderId("legacy-unverified");
            stub.setPrice(row.unitPrice().toPlainString());

            TradeItem item = new TradeItem(stub, false, row.qty());
            item.setUnitPrice(row.unitPrice());
            item.setQuantity(row.qty());


            BigDecimal total = row.unitPrice().multiply(BigDecimal.valueOf(row.qty()));
            tableModel.addTrade(item, row.condition(), row.unitPrice());
        }
        refreshSummary();

        // Fetch image URLs for restored cards in the background so the hover
        // popup works on the restored rows (stubs have no imageUrl yet).
        List<Card> stubs = new ArrayList<>();
        for (TradeItem item : receivedCards) stubs.add(item.getCard());
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                ScryfallCatalogService catalog = ScryfallCatalogService.getInstance();
                for (Card stub : stubs) {
                    if ("MISC".equalsIgnoreCase(stub.getSetCode())) continue;
                    // Try catalog first — no network call, no sleep needed
                    java.util.Optional<Card> hit = catalog.lookup(
                            stub.getSetCode(), stub.getCollectorNumber());
                    if (hit.isPresent() && hit.get().getImageUrl() != null) {
                        stub.setImageUrl(hit.get().getImageUrl());
                        continue;
                    }
                    try {
                        Card fetched = apiService.fetchCard(stub.getSetCode(), stub.getCollectorNumber());
                        stub.setImageUrl(fetched.getImageUrl());
                    } catch (Exception ignored) {}
                    try { Thread.sleep(110); } catch (InterruptedException ignored) { break; }
                }
                return null;
            }
        }.execute();
    }

    private boolean finishCellEdit() {
        return !cardTable.isEditing() || cardTable.getCellEditor().stopCellEditing();
    }

    private void resumeDraft(com.cardpricer.service.TradeRepository.RecoveryDraft recovery) {
        resetTradeState(false);
        editingRevision=recovery.editingRevision();
        restoreDraft(recovery.draft());
        if(editingRevision!=null) showEditingState();
        draftSaveStatus.setText("Draft restored");
    }

    private void markDraftChanged() {
        draftChangeGeneration++;
        draftSaveStatus.setText("Unsaved changes");
        autosaveTimer.restart();
    }

    /** Capture Swing state now; encode, validate and persist on the bounded save worker. */
    private void performAutosave() {
        if(tableModel.getRowCount()==0 && editingRevision==null) return;
        var id=draftId;long generation=draftChangeGeneration;
        try {
            var capture=captureDraft();
            draftSaveStatus.setText("Saving draft…");
            TradeSessionService.queueDraft(id,capture,editingRevision,failure -> SwingUtilities.invokeLater(()-> {
                if(!id.equals(draftId) || generation!=draftChangeGeneration) return;
                draftSaveStatus.setText(failure==null ? "Draft saved locally" : "Draft not saved — retry using Save for later");
                draftSaveStatus.setToolTipText(failure==null ? null : failure.getMessage());
            }));
        } catch(RuntimeException failure) {
            draftSaveStatus.setText("Draft not saved");draftSaveStatus.setToolTipText(failure.getMessage());
            throw failure;
        }
    }

    // -------------------------------------------------------------------------
    // F1+F2: Bounty-aware row tint renderer
    // -------------------------------------------------------------------------

    /**
     * Renders table cells with a gold background for rows whose payout came from a bounty override.
     */
    private class BountyAwareRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            Component c = super.getTableCellRendererComponent(
                    table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
                int modelRow = table.convertRowIndexToModel(row);
                if (modelRow >= 0 && modelRow < rowPayouts.size()
                        && rowPayouts.get(modelRow) != null
                        && rowPayouts.get(modelRow).isBounty()) {
                    c.setBackground(new Color(42, 122, 122));
                    c.setForeground(Color.WHITE);
                } else {
                    c.setBackground(table.getBackground());
                    c.setForeground(table.getForeground());
                }
            }
            return c;
        }
    }
}
