package com.cardpricer.gui.panel;

import com.cardpricer.gui.ShortcutHelpDialog;
import com.cardpricer.model.TradeRecord;
import com.cardpricer.util.AppTheme;
import com.cardpricer.service.ReceiptPrintService;
import com.cardpricer.service.TradeHistoryService;
import com.cardpricer.service.TradeDeletionService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Panel for browsing, downloading, printing, and deleting generated data files.
 * Contains three tabs: Local Files, Shared Files (configured via Preferences), and
 * History (trade receipts with preview and print/PDF support).
 */
public class FileManagerPanel extends JPanel {

    private static final String   HELP_TITLE = "File Manager — Help";
    private static final String[] HELP_COLS  = {"Tab / Feature", "Description"};
    private static final String[][] HELP_ROWS = {
        {"--- Tabs", ""},
        {"Local Files",     "CSV imports saved by this app on this machine"},
        {"Shared Files",    "CSV imports in the shared network folder (set in Preferences)"},
        {"History",         "Browse and preview past trade receipts"},
        {"--- Local / Shared", ""},
        {"Filter",          "Narrow the file list by category"},
        {"Refresh",         "Reload the file list from disk"},
        {"Open",            "Open the file with your default application"},
        {"Copy to Local",   "Copy a shared file into local storage"},
        {"Copy file path",  "Copy full CSV paths; History copies the selected trade's CSV import"},
        {"Delete",          "Remove selected trades and their files everywhere; other exports are deleted locally"},
        {"--- History", ""},
        {"Search",          "Search all trade history by customer, date, or receipt text"},
        {"Load 100 more",   "Show older trades; totals include all matching trades"},
        {"Print",           "Send the selected trade receipt to a printer"},
        {"Save PDF",        "Export the selected trade receipt as a PDF"},
        {"Open in Explorer","Reveal the receipt file in Windows Explorer"},
    };

    // Local files tab
    private JTable fileTable;
    private JTabbedPane tabs;
    private DefaultTableModel tableModel;
    private JComboBox<String> categoryCombo;
    private JLabel statusLabel;

    // Shared files tab
    private JTable sharedTable;
    private DefaultTableModel sharedTableModel;
    private JLabel sharedStatusLabel;

    // History tab
    private JTable historyTable;
    private DefaultTableModel historyTableModel;
    private JTextArea historyPreviewArea;
    private JTextField historySearchField;
    private JComboBox<String> historyPaymentCombo;   // F12
    private JLabel historyStatusLabel;
    private JComboBox<String> historyInventoryFilter;
    private JCheckBox inventoriedCheck;
    private JButton editTradeButton;
    private JButton revisionHistoryButton;
    private JButton histPrintBtn, histPdfBtn, historyLoadMore;
    private JButton deleteTradeButton;
    private boolean deletionPending;
    private long fileListGeneration,sharedFileListGeneration;
    private boolean inventorySavePending;
    private JLabel inventorySyncLabel;
    private boolean historyRefreshRunning;
    private long inventorySyncGeneration=-1;
    private long lastHistoryScan;
    private boolean historyRefreshRequested, renderingHistory;
    private TradeHistoryService.Session historySession;
    private TradeHistoryService.Snapshot historySnapshot;
    private String historySharedFolder;
    private int historyLimit=100;
    private long historyMatchCount;
    private TradeHistoryService.Page historyPage;
    private String completedHistoryQuery="", previewToken;
    private long searchGeneration, previewGeneration;
    private SwingWorker<?,?> searchWorker, previewWorker;
    private final Timer historySearchTimer=new Timer(250,e->searchHistory());
    private final Timer inventoryRefreshTimer=new Timer(2_000,e -> {
        if (!isShowing() || tabs.getSelectedIndex()!=2) return;
        inventorySyncLabel.setText(com.cardpricer.service.InventoryStatusSyncService.status());
        if (inventorySyncGeneration!=com.cardpricer.service.InventoryStatusSyncService.generation()
                || System.currentTimeMillis()-lastHistoryScan>=15_000) refreshHistoryList(false);
    });
    private java.util.function.Consumer<com.cardpricer.model.TradeDraft> tradeEditor;
    private List<TradeRecord> allRecords = new ArrayList<>();

    // Only recently selected previews live in memory; full-text search uses the persistent index.
    private final Map<String, String> contentCache = new java.util.LinkedHashMap<>(32,0.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,String> entry) { return size()>32; }
    };

    // F13: Stats bar labels
    private JLabel historyStatsTotalTrades;
    private JLabel historyStatsTotalCards;
    private JLabel historyStatsTotalValue;
    private JLabel historyStatsAvgValue;

    private static final DateTimeFormatter HISTORY_DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String[] CATEGORIES = {
            "All Files",
            "Trades",
            "Set Pricer",
            "Inventory",
            "Combined Files"
    };

    /** Constructs the File Manager panel and loads the local file list immediately. */
    public FileManagerPanel() {
        historySearchTimer.setRepeats(false);
        setLayout(new BorderLayout(15, 15));
        setBorder(new EmptyBorder(16, 20, 14, 20));

        add(createTopPanel(), BorderLayout.NORTH);

        tabs = new JTabbedPane();
        tabs.addTab("Local Files",  createLocalTab());
        tabs.addTab("Shared Files", createSharedTab());
        tabs.addTab("History",      createHistoryTab());

        tabs.addChangeListener(e -> {
            int idx = tabs.getSelectedIndex();
            if (idx == 1) refreshSharedFileList();
            if (idx == 2) refreshHistoryList();
        });
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED)!=0
                    && isShowing() && tabs.getSelectedIndex()==2) refreshHistoryList();
        });
        add(tabs, BorderLayout.CENTER);

        // Load local files on startup
        refreshFileList();
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));

        JPanel titlePanel = AppTheme.panelHeader("Files & history",
                "Browse CSV imports and trade history");

        JButton helpBtn = new JButton("?");


        helpBtn.setFont(helpBtn.getFont().deriveFont(Font.BOLD, 14f));
        helpBtn.setToolTipText("Help");
        helpBtn.addActionListener(e ->
                ShortcutHelpDialog.show(SwingUtilities.getWindowAncestor(this),
                        HELP_TITLE, HELP_COLS, HELP_ROWS));

        JPanel titleRow = new JPanel(new BorderLayout(10, 0));
        titleRow.add(titlePanel, BorderLayout.CENTER);
        JPanel headerActions = AppTheme.transparent(new com.cardpricer.gui.WrapLayout(FlowLayout.RIGHT, 0, 0));
        headerActions.add(helpBtn);
        titleRow.add(headerActions, BorderLayout.EAST);

        panel.add(titleRow, BorderLayout.NORTH);

        // Filter section
        JPanel filterPanel = AppTheme.surface(new com.cardpricer.gui.WrapLayout(FlowLayout.LEFT, 10, 0), 16);

        JLabel filterLabel = new JLabel("Category:");
        categoryCombo = new JComboBox<>(CATEGORIES);
        categoryCombo.setPreferredSize(new Dimension(180, 32));
        categoryCombo.addActionListener(e -> refreshFileList());

        JButton refreshButton = new JButton("Refresh");


        refreshButton.addActionListener(e -> refreshFileList());

        filterPanel.add(filterLabel);
        filterPanel.add(categoryCombo);
        filterPanel.add(refreshButton);

        panel.add(filterPanel, BorderLayout.CENTER);

        // Status section
        statusLabel = new JLabel("Ready");
        statusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(statusLabel, BorderLayout.SOUTH);

        return panel;
    }

    /** Allows the dashboard history action to open the requested tab directly. */
    public void showHistory() { if (tabs.getSelectedIndex()==2) refreshHistoryList(); else tabs.setSelectedIndex(2); }

    public void setTradeEditor(java.util.function.Consumer<com.cardpricer.model.TradeDraft> editor) { tradeEditor=editor; }

    @Override public void addNotify() {
        super.addNotify();inventoryRefreshTimer.start();
        if(historySnapshot!=null) { filterHistory(false);loadHistoryPreview(); }
    }
    @Override public void removeNotify() {
        inventoryRefreshTimer.stop();historySearchTimer.stop();
        historyGeneration++;historyRefreshRequested=false;
        searchGeneration++;previewGeneration++;
        if(searchWorker!=null) searchWorker.cancel(true);
        if(previewWorker!=null) previewWorker.cancel(true);
        super.removeNotify();
    }

    private JPanel createLocalTab() {
        JPanel tab = new JPanel(new BorderLayout(10, 10));
        tab.add(createTablePanel(), BorderLayout.CENTER);
        tab.add(createBottomPanel(), BorderLayout.SOUTH);
        return tab;
    }

    // ── Shared Files Tab ─────────────────────────────────────────────────────

    private JPanel createSharedTab() {
        JPanel tab = new JPanel(new BorderLayout(10, 10));

        // Header
        JPanel header = new JPanel(new com.cardpricer.gui.WrapLayout(FlowLayout.LEFT, 10, 6));
        sharedStatusLabel = new JLabel("Configure a shared folder in Preferences → Network");
        sharedStatusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        JButton refreshBtn = new JButton("Refresh");

        refreshBtn.addActionListener(e -> refreshSharedFileList());
        header.add(sharedStatusLabel);
        header.add(refreshBtn);

        // Table
        String[] cols = {"Filename", "Type", "Size", "Date Modified", "Path"};
        sharedTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        sharedTable = new com.cardpricer.gui.EmptyStateTable(sharedTableModel, "Your shared files will appear here", "Configure a shared folder in Preferences to connect this workspace.");
        sharedTable.setFont(sharedTable.getFont().deriveFont(14f));
        AppTheme.styleTable(sharedTable);
        sharedTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        TableRowSorter<DefaultTableModel> sharedSorter = new TableRowSorter<>(sharedTableModel);
        sharedSorter.setComparator(2, FileManagerPanel::compareSizeStrings); // Size — numeric
        sharedSorter.setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.DESCENDING))); // newest first
        sharedTable.setRowSorter(sharedSorter);
        sharedTable.getColumnModel().getColumn(0).setPreferredWidth(300);
        sharedTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        sharedTable.getColumnModel().getColumn(2).setPreferredWidth(80);
        sharedTable.getColumnModel().getColumn(3).setPreferredWidth(150);
        sharedTable.getColumnModel().getColumn(4).setPreferredWidth(250);

        JScrollPane scroll = new com.cardpricer.gui.ResponsiveTableScroll(sharedTable, 200, 90, 80, 140, 180);
        scroll.setColumnHeaderView(sharedTable.getTableHeader()); scroll.setBorder(AppTheme.cardBorder(0));

        // Buttons
        JPanel btnPanel = new JPanel(new com.cardpricer.gui.WrapLayout(FlowLayout.RIGHT, 10, 5));
        JButton openBtn = new JButton("Open");


        openBtn.addActionListener(e -> openSharedFile());

        JButton copyBtn = new JButton("Copy to Local");


        copyBtn.addActionListener(e -> copySharedToLocal());

        btnPanel.add(openBtn);
        btnPanel.add(createCopyPathButton(sharedTable,
                row -> (String) sharedTableModel.getValueAt(row, 4), sharedStatusLabel));
        btnPanel.add(copyBtn);
        JButton deleteShared=AppTheme.dangerButton("Delete Selected");
        deleteShared.addActionListener(e->deleteFiles(sharedTable,sharedTableModel));
        btnPanel.add(deleteShared);

        tab.add(header, BorderLayout.NORTH);
        tab.add(scroll,  BorderLayout.CENTER);
        tab.add(btnPanel, BorderLayout.SOUTH);
        return tab;
    }

    private void refreshSharedFileList() {
        long generation=++sharedFileListGeneration;
        sharedTableModel.setRowCount(0);
        String path = PreferencesPanel.getSharedTradesFolder();
        if (path == null || path.isBlank()) {
            sharedStatusLabel.setText("No shared folder configured — go to Preferences → Network");
            return;
        }
        sharedStatusLabel.setText("Connecting to shared folder...");

        // Run all network I/O off the EDT — File.exists()/listFiles() on a UNC path can
        // block for 30+ seconds when the host is on a different network segment.
        new SwingWorker<List<Object[]>, Void>() {
            @Override
            protected List<Object[]> doInBackground() throws Exception {
                File dir = new File(path);
                if (!dir.exists() || !dir.isDirectory()) return null;
                var deletions=deletionService(path);deletions.sync();
                var deleted=deletions.deletedFileNames();
                SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                File[] files = dir.listFiles(FileManagerPanel::isCsvImport);
                List<Object[]> rows = new ArrayList<>();
                if (files != null) {
                    for (File f : files) {
                        if(deleted.contains(f.getName().toLowerCase(java.util.Locale.ROOT))) continue;
                        rows.add(new Object[]{
                                f.getName(),
                                "CSV",
                                formatFileSize(f.length()),
                                fmt.format(new Date(f.lastModified())),
                                f.getAbsolutePath()
                        });
                    }
                }
                return rows;
            }
            @Override
            protected void done() {
                if(generation!=sharedFileListGeneration) return;
                List<Object[]> rows;
                try { rows = get(); } catch (Exception ex) { rows = null; }
                if (rows == null) {
                    sharedStatusLabel.setText("Shared folder not accessible: " + path);
                    return;
                }
                for (Object[] row : rows) sharedTableModel.addRow(row);
                sharedStatusLabel.setText("Shared folder: " + path + "  (" + rows.size() + " file(s))");
            }
        }.execute();
    }

    private void openSharedFile() {
        int row = sharedTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Please select a file.", "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String filePath = (String) sharedTableModel.getValueAt(sharedTable.convertRowIndexToModel(row), 4);
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(new File(filePath));
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Failed to open file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void copySharedToLocal() {
        int row = sharedTable.getSelectedRow();
        if (row < 0) {
            JOptionPane.showMessageDialog(this, "Please select a file.", "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int modelRow = sharedTable.convertRowIndexToModel(row);
        String srcPath = (String) sharedTableModel.getValueAt(modelRow, 4);
        String filename = (String) sharedTableModel.getValueAt(modelRow, 0);
        String shared=PreferencesPanel.getSharedTradesFolder();
        new SwingWorker<Void,Void>() {
            protected Void doInBackground() throws Exception {
                deletionService(shared).copyToLocal(Path.of(srcPath));return null;
            }
            protected void done() {
                try {
                    get();JOptionPane.showMessageDialog(FileManagerPanel.this,"Copied to local folder: "+filename,"Copy Complete",JOptionPane.INFORMATION_MESSAGE);
                    refreshFileList();
                } catch(Exception failure) { JOptionPane.showMessageDialog(FileManagerPanel.this,errorMessage(failure),"Could not copy file",JOptionPane.ERROR_MESSAGE); }
            }
        }.execute();
    }

    // ── Local Files Tab ───────────────────────────────────────────────────────

    private JPanel createTablePanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));

        // Table columns: Filename, Type, Size, Date Modified
        String[] columns = {"Filename", "Type", "Size", "Date Modified", "Path"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false; // All cells non-editable
            }
        };

        fileTable = new com.cardpricer.gui.EmptyStateTable(tableModel, "A home for your CSV imports", "Trade, pricing, and inventory CSV exports appear here.");
        fileTable.setFont(fileTable.getFont().deriveFont(14f));
        AppTheme.styleTable(fileTable);
        fileTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);

        // Column widths
        fileTable.getColumnModel().getColumn(0).setPreferredWidth(300); // Filename
        fileTable.getColumnModel().getColumn(1).setPreferredWidth(100); // Type
        fileTable.getColumnModel().getColumn(2).setPreferredWidth(80);  // Size
        fileTable.getColumnModel().getColumn(3).setPreferredWidth(150); // Date
        fileTable.getColumnModel().getColumn(4).setPreferredWidth(250); // Path

        // Enable table sorting — newest first, numeric size/amount comparators
        TableRowSorter<DefaultTableModel> fileSorter = new TableRowSorter<>(tableModel);
        fileSorter.setComparator(2, FileManagerPanel::compareSizeStrings); // Size — numeric
        fileSorter.setSortKeys(List.of(new RowSorter.SortKey(3, SortOrder.DESCENDING))); // newest first
        fileTable.setRowSorter(fileSorter);

        JScrollPane scrollPane = new com.cardpricer.gui.ResponsiveTableScroll(fileTable, 200, 90, 80, 140, 180);
        scrollPane.setColumnHeaderView(fileTable.getTableHeader()); scrollPane.setBorder(AppTheme.cardBorder(0));

        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));

        // Left: Info
        JLabel infoLabel = new JLabel("Select files to download or delete.");
        infoLabel.setToolTipText("Select files, then choose Download Selected to save them to a folder.");
        infoLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(infoLabel, BorderLayout.CENTER);

        // Right: Action buttons
        JPanel buttonPanel = new JPanel(new com.cardpricer.gui.WrapLayout(FlowLayout.RIGHT, 10, 5));

        JButton downloadButton = new JButton("Download Selected");


        downloadButton.addActionListener(e -> downloadSelected());

        JButton openFolderButton = new JButton("Open in File Explorer");


        openFolderButton.addActionListener(e -> openSelectedFolder());

        JButton deleteButton = AppTheme.dangerButton("Delete Selected");

        deleteButton.addActionListener(e -> deleteSelected());

        buttonPanel.add(openFolderButton);
        buttonPanel.add(createCopyPathButton(fileTable,
                row -> (String) tableModel.getValueAt(row, 4), statusLabel));
        buttonPanel.add(downloadButton);
        buttonPanel.add(deleteButton);

        panel.add(buttonPanel, BorderLayout.EAST);

        return panel;
    }

    private JButton createCopyPathButton(JTable table, java.util.function.IntFunction<String> pathAtRow,
                                         JLabel feedback) {
        JButton button = new JButton("Copy file path");
        button.setToolTipText("Copy the full CSV import path; multiple selected paths are copied on separate lines.");
        button.setEnabled(table.getSelectedRowCount() > 0);
        table.getSelectionModel().addListSelectionListener(e ->
                button.setEnabled(table.getSelectedRowCount() > 0));
        button.addActionListener(e -> {
            int[] selectedRows = table.getSelectedRows();
            if (selectedRows.length == 0) return;
            List<Path> paths = new ArrayList<>();
            for (int row : selectedRows) {
                String path = pathAtRow.apply(table.convertRowIndexToModel(row));
                paths.add(Path.of(path).toAbsolutePath().normalize());
            }
            button.setEnabled(false);
            feedback.setText("Checking CSV files...");
            // Shared paths can be slow. Check availability off the EDT before copying.
            new SwingWorker<Boolean, Void>() {
                @Override protected Boolean doInBackground() {
                    return paths.stream().allMatch(Files::isRegularFile);
                }
                @Override protected void done() {
                    try {
                        if (!get()) {
                            feedback.setText("CSV import file unavailable. Refresh or retry the trade export.");
                            return;
                        }
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(
                                String.join(System.lineSeparator(), paths.stream().map(Path::toString).toList())), null);
                        feedback.setText(paths.size() == 1 ? "CSV file path copied" : paths.size() + " CSV file paths copied");
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(FileManagerPanel.this, "Could not copy the CSV file path. Please try again.",
                                "Copy unavailable", JOptionPane.ERROR_MESSAGE);
                    } finally { button.setEnabled(table.getSelectedRowCount() > 0); }
                }
            }.execute();
        });
        return button;
    }

    private void refreshFileList() {
        long generation=++fileListGeneration;
        tableModel.setRowCount(0);
        statusLabel.setText("Loading files…");
        final String selectedCategory = (String) categoryCombo.getSelectedItem();

        new SwingWorker<List<Object[]>, Void>() {
            @Override
            protected List<Object[]> doInBackground() throws Exception {
                List<File> files = new ArrayList<>();
                if ("All Files".equals(selectedCategory)) {
                    files.addAll(getFilesFromDirectory(com.cardpricer.util.AppDataDirectory.tradesPath()));
                    files.addAll(getFilesFromDirectory(com.cardpricer.util.AppDataDirectory.pricesPath()));
                    files.addAll(getFilesFromDirectory(com.cardpricer.util.AppDataDirectory.combinedFilesPath()));
                    files.addAll(getFilesFromDirectory(com.cardpricer.util.AppDataDirectory.inventoryPath()));
                } else if ("Trades".equals(selectedCategory)) {
                    files.addAll(getFilesFromDirectory(com.cardpricer.util.AppDataDirectory.tradesPath()));
                } else if ("Set Pricer".equals(selectedCategory)) {
                    files.addAll(getFilesFromDirectory(com.cardpricer.util.AppDataDirectory.pricesPath()));
                } else if ("Inventory".equals(selectedCategory)) {
                    files.addAll(getFilesFromDirectory(com.cardpricer.util.AppDataDirectory.inventoryPath()));
                } else if ("Combined Files".equals(selectedCategory)) {
                    files.addAll(getFilesFromDirectory(com.cardpricer.util.AppDataDirectory.combinedFilesPath()));
                }
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                var deleted=deletionService("").deletedFileNames();
                Path trades=com.cardpricer.util.AppDataDirectory.trades().toPath().toAbsolutePath().normalize();
                List<Object[]> rows = new ArrayList<>();
                for (File file : files) {
                    if(file.toPath().toAbsolutePath().normalize().getParent().equals(trades)
                            && deleted.contains(file.getName().toLowerCase(java.util.Locale.ROOT))) continue;
                    if (file.isFile()) {
                        rows.add(new Object[]{
                                file.getName(), "CSV",
                                formatFileSize(file.length()),
                                dateFormat.format(new Date(file.lastModified())),
                                file.getAbsolutePath()
                        });
                    }
                }
                return rows;
            }
            @Override
            protected void done() {
                if(generation!=fileListGeneration) return;
                try {
                    List<Object[]> rows = get();
                    for (Object[] row : rows) tableModel.addRow(row);
                    statusLabel.setText(String.format(java.util.Locale.ROOT, "Found %d file(s)", rows.size()));
                } catch (Exception ex) {
                    statusLabel.setText("Failed to load files");
                }
            }
        }.execute();
    }

    private List<File> getFilesFromDirectory(String dirPath) {
        List<File> files = new ArrayList<>();
        File dir = new File(dirPath);

        if (dir.exists() && dir.isDirectory()) {
            File[] fileArray = dir.listFiles(FileManagerPanel::isCsvImport);
            if (fileArray != null) {
                files.addAll(Arrays.asList(fileArray));
            }
        }

        return files;
    }

    private static boolean isCsvImport(File file) {
        return file.isFile() && file.getName().toLowerCase(java.util.Locale.ROOT).endsWith(".csv");
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(java.util.Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /** Parses a size string produced by {@link #formatFileSize} back to bytes for numeric sorting. */
    private static long parseSizeBytes(String s) {
        try {
            if (s.endsWith(" B"))  return Long.parseLong(s.replace(" B", "").trim());
            if (s.endsWith(" KB")) return (long)(Double.parseDouble(s.replace(" KB", "").trim()) * 1024);
            if (s.endsWith(" MB")) return (long)(Double.parseDouble(s.replace(" MB", "").trim()) * 1024 * 1024);
        } catch (Exception ignored) {}
        return 0;
    }

    /** Comparator for size strings: numeric order rather than lexicographic. */
    private static int compareSizeStrings(Object a, Object b) {
        return Long.compare(parseSizeBytes(a.toString()), parseSizeBytes(b.toString()));
    }

    // ── History Tab ───────────────────────────────────────────────────────────

    private JPanel createHistoryTab() {
        JPanel tab = new JPanel(new BorderLayout(8, 8));
        tab.setBorder(new EmptyBorder(6, 0, 0, 0));

        // ── Top: filter row ──────────────────────────────────────────────────
        JPanel filterRow = new JPanel(new com.cardpricer.gui.WrapLayout(FlowLayout.LEFT, 10, 4));
        filterRow.add(new JLabel("Search:"));
        historySearchField = new JTextField(20);
        historySearchField.setToolTipText("Filter by customer, date, or receipt body text");
        historySearchField.getDocument().addDocumentListener(
                new javax.swing.event.DocumentListener() {
                    public void insertUpdate(javax.swing.event.DocumentEvent e) { applyHistoryFilter(); }
                    public void removeUpdate(javax.swing.event.DocumentEvent e) { applyHistoryFilter(); }
                    public void changedUpdate(javax.swing.event.DocumentEvent e) {}
                });
        filterRow.add(historySearchField);

        // F12: Payment type filter
        filterRow.add(new JLabel("  Payment:"));
        historyPaymentCombo = new JComboBox<>(new String[]{"All", "Credit", "Check", "Partial", "Inventory"});
        historyPaymentCombo.addActionListener(e -> applyHistoryFilter());
        filterRow.add(historyPaymentCombo);
        historyInventoryFilter=new JComboBox<>(new String[]{"All POS statuses", "Needs inventory", "Inventoried"});
        historyInventoryFilter.addActionListener(e -> applyHistoryFilter());
        filterRow.add(historyInventoryFilter);

        JButton refreshBtn = new JButton("Refresh");

        refreshBtn.addActionListener(e -> refreshHistoryList());
        filterRow.add(refreshBtn);

        historyStatusLabel = new JLabel("Loading…");
        historyStatusLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        filterRow.add(historyStatusLabel);

        tab.add(filterRow, BorderLayout.NORTH);

        // ── Left pane: trade list ────────────────────────────────────────────
        String[] cols = {"Date", "Customer", "Payment Type", "Total Value", "# Cards", "In POS"};
        historyTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) { return c==5 ? Boolean.class : super.getColumnClass(c); }
        };
        historyTable = new JTable(historyTableModel);
        historyTable.setFont(historyTable.getFont().deriveFont(13f));
        historyTable.setRowHeight(26);
        historyTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        TableRowSorter<DefaultTableModel> historySorter = new TableRowSorter<>(historyTableModel);
        historySorter.setComparator(3, (a, b) -> {   // Total Value — numeric
            try {
                double va = Double.parseDouble(a.toString().replace("$", "").replace(",", "").trim());
                double vb = Double.parseDouble(b.toString().replace("$", "").replace(",", "").trim());
                return Double.compare(va, vb);
            } catch (Exception ignored) { return a.toString().compareTo(b.toString()); }
        });
        historySorter.setComparator(4, (a, b) -> {   // # Cards — numeric
            try { return Integer.compare(Integer.parseInt(a.toString()), Integer.parseInt(b.toString())); }
            catch (Exception ignored) { return a.toString().compareTo(b.toString()); }
        });
        historySorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.DESCENDING))); // newest first
        historyTable.setRowSorter(historySorter);
        historyTable.getColumnModel().getColumn(0).setPreferredWidth(130);
        historyTable.getColumnModel().getColumn(1).setPreferredWidth(140);
        historyTable.getColumnModel().getColumn(2).setPreferredWidth(170);
        historyTable.getColumnModel().getColumn(3).setPreferredWidth(90);
        historyTable.getColumnModel().getColumn(4).setPreferredWidth(60);

        // When a row is selected, load the file into the preview pane
        historyTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) loadHistoryPreview();
        });

        JScrollPane listScroll = new com.cardpricer.gui.ResponsiveTableScroll(historyTable, 130, 140, 130, 100, 70, 65);
        listScroll.setColumnHeaderView(historyTable.getTableHeader()); listScroll.setBorder(AppTheme.cardBorder(0));

        // ── Right pane: preview ──────────────────────────────────────────────
        historyPreviewArea = new JTextArea();
        historyPreviewArea.setEditable(false);
        historyPreviewArea.setFont(new Font("Monospaced", Font.PLAIN, 13));
        historyPreviewArea.setLineWrap(false);
        JScrollPane previewScroll = new JScrollPane(historyPreviewArea);
        previewScroll.setBorder(AppTheme.sectionBorder("Receipt preview"));

        // Action buttons below preview
        histPrintBtn = new JButton("Print");
        histPrintBtn.setEnabled(false);


        histPrintBtn.addActionListener(e -> historyPrint());

        histPdfBtn = new JButton("Save as PDF");
        histPdfBtn.setEnabled(false);


        histPdfBtn.addActionListener(e -> historySaveAsPdf());

        JButton histOpenBtn = new JButton("Open in Explorer");


        histOpenBtn.addActionListener(e -> historyOpenInExplorer());

        JPanel previewBtns = new JPanel(new com.cardpricer.gui.WrapLayout(FlowLayout.RIGHT, 8, 4));
        editTradeButton=new JButton("Edit trade");
        editTradeButton.setEnabled(false);
        editTradeButton.addActionListener(e -> editHistoryTrade());
        previewBtns.add(editTradeButton);
        revisionHistoryButton=new JButton("Revision history");
        revisionHistoryButton.setEnabled(false);
        revisionHistoryButton.addActionListener(e -> showRevisionHistory());
        previewBtns.add(revisionHistoryButton);
        deleteTradeButton=AppTheme.dangerButton("Delete trade");
        deleteTradeButton.setEnabled(false);
        deleteTradeButton.addActionListener(e->deleteHistoryTrade());
        previewBtns.add(deleteTradeButton);
        previewBtns.add(histPrintBtn);
        previewBtns.add(histPdfBtn);
        previewBtns.add(histOpenBtn);
        previewBtns.add(createCopyPathButton(historyTable,
                row -> TradeHistoryService.csvPath(visibleRecords.get(row)).toString(), historyStatusLabel));

        JPanel rightPanel = new JPanel(new BorderLayout());
        inventoriedCheck=new JCheckBox("Inventoried into POS");
        inventoriedCheck.setEnabled(false);
        inventoriedCheck.setToolTipText("Shared across computers using the same shared trades folder. Corrections need POS review again.");
        inventoriedCheck.addActionListener(e -> saveInventoryStatus());
        JPanel inventoryStatus=new JPanel(new BorderLayout(0,4));
        inventoryStatus.add(inventoriedCheck,BorderLayout.NORTH);
        inventorySyncLabel=AppTheme.mutedLabel(com.cardpricer.service.InventoryStatusSyncService.status());
        inventoryStatus.add(inventorySyncLabel,BorderLayout.SOUTH);
        rightPanel.add(inventoryStatus,BorderLayout.NORTH);
        rightPanel.add(previewScroll, BorderLayout.CENTER);
        rightPanel.add(previewBtns,  BorderLayout.SOUTH);

        // ── Split pane ───────────────────────────────────────────────────────
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, rightPanel);
        split.setDividerLocation(420);
        split.setResizeWeight(0.45);
        tab.add(split, BorderLayout.CENTER);

        // ── F13: Stats bar ────────────────────────────────────────────────────
        historyStatsTotalTrades = new JLabel("Trades: 0");
        historyStatsTotalCards  = new JLabel("  Cards: 0");
        historyStatsTotalValue  = new JLabel("  Total: $0.00");
        historyStatsAvgValue    = new JLabel("  Avg: $0.00");

        Font statsFont = historyStatsTotalTrades.getFont().deriveFont(12f);
        Color statsFg  = UIManager.getColor("Label.disabledForeground");
        for (JLabel lbl : new JLabel[]{
                historyStatsTotalTrades, historyStatsTotalCards,
                historyStatsTotalValue, historyStatsAvgValue}) {
            lbl.setFont(statsFont);
            lbl.setForeground(statsFg);
        }

        JPanel statsBar = new JPanel(new com.cardpricer.gui.WrapLayout(FlowLayout.LEFT, 4, 4));
        statsBar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                UIManager.getColor("Separator.foreground")));
        statsBar.add(historyStatsTotalTrades);
        statsBar.add(historyStatsTotalCards);
        statsBar.add(historyStatsTotalValue);
        statsBar.add(historyStatsAvgValue);
        historyLoadMore=new JButton("Load 100 more");
        historyLoadMore.setVisible(false);
        historyLoadMore.addActionListener(e->loadHistoryPage(true));
        statsBar.add(historyLoadMore);
        tab.add(statsBar, BorderLayout.SOUTH);

        return tab;
    }

    private long historyGeneration;

    private void refreshHistoryList() { refreshHistoryList(true); }

    private void refreshHistoryList(boolean requestSync) {
        if(historyRefreshRunning || inventorySavePending || deletionPending) {
            if(requestSync) historyRefreshRequested=true;
            return;
        }
        historyRefreshRunning=true;historyRefreshRequested=false;
        if(requestSync) com.cardpricer.service.InventoryStatusSyncService.requestSync();
        long syncGeneration=com.cardpricer.service.InventoryStatusSyncService.generation();
        long generation=++historyGeneration,filterGeneration=searchGeneration;
        String shared=PreferencesPanel.getSharedTradesFolder();
        var existing=java.util.Objects.equals(shared,historySharedFolder) ? historySession : null;
        var filter=currentHistoryFilter();int limit=historyLimit;
        if(historySnapshot==null) historyStatusLabel.setText("Loading history...");
        new SwingWorker<TradeHistoryService.Page,TradeHistoryService.Page>() {
            private TradeHistoryService.Session session;
            protected TradeHistoryService.Page doInBackground() throws Exception {
                session=existing==null ? new TradeHistoryService.Session(com.cardpricer.util.AppDataDirectory.tradesPath(),shared) : existing;
                publish(session.page(filter,limit,null,""));
                String notice=session.refreshIndex();
                return session.page(filter,limit,null,notice);
            }
            protected void process(List<TradeHistoryService.Page> pages) {
                if(generation!=historyGeneration) return;
                historySession=session;historySharedFolder=shared;
                if(filterGeneration==searchGeneration) acceptHistoryPage(pages.getLast(),false,filter.query());
            }
            protected void done() {
                try {
                    if(generation!=historyGeneration) return;
                    var page=get();historySession=session;historySharedFolder=shared;
                    if(filterGeneration==searchGeneration) acceptHistoryPage(page,false,filter.query());
                    else searchHistory();
                    inventorySyncGeneration=syncGeneration;
                } catch(Exception failure) { historyStatusLabel.setText("Could not refresh history: "+errorMessage(failure)); }
                finally {
                    historyRefreshRunning=false;lastHistoryScan=System.currentTimeMillis();
                    if(historyRefreshRequested) refreshHistoryList();
                }
            }
        }.execute();
    }

    private TradeHistoryService.Filter currentHistoryFilter() {
        return new TradeHistoryService.Filter(historyQuery(),(String)historyPaymentCombo.getSelectedItem(),historyInventoryFilter.getSelectedIndex());
    }

    private void acceptHistoryPage(TradeHistoryService.Page page,boolean append,String query) {
        if(append && historySnapshot!=null) {
            var records=new ArrayList<>(historySnapshot.records());records.addAll(page.snapshot().records());
            var sources=new java.util.HashMap<>(historySnapshot.sources());sources.putAll(page.snapshot().sources());
            var tokens=new java.util.HashMap<>(historySnapshot.tokens());tokens.putAll(page.snapshot().tokens());
            page=new TradeHistoryService.Page(new TradeHistoryService.Snapshot(records,sources,tokens,page.snapshot().notice()),
                    page.count(),page.cards(),page.total(),page.next());
        }
        var snapshot=page.snapshot();
        boolean same=historySnapshot!=null && historySnapshot.tokens().equals(snapshot.tokens()) && allRecords.size()==snapshot.records().size();
        if(same) for(int i=0;i<allRecords.size();i++) {
            var old=allRecords.get(i);var next=snapshot.records().get(i);
            if(!old.historyKey().equals(next.historyKey()) || old.inventoried!=next.inventoried) { same=false;break; }
        }
        historyPage=page;historySnapshot=snapshot;completedHistoryQuery=query;
        historyMatchCount=page.count();
        historyLimit=Math.max(100,snapshot.records().size());
        allRecords=new ArrayList<>(snapshot.records());
        contentCache.keySet().retainAll(snapshot.tokens().values());
        if(!same) renderHistory();
        updateHistoryStats();updateHistoryCount();
    }

    private final java.util.List<TradeRecord> visibleRecords = new java.util.ArrayList<>();

    private String historyQuery() { return historySearchField.getText().trim().toLowerCase(java.util.Locale.ROOT); }

    private void applyHistoryFilter() { filterHistory(true); }

    private void filterHistory(boolean resetPage) {
        if(resetPage) historyLimit=100;
        historySearchTimer.stop();searchGeneration++;
        if(searchWorker!=null) searchWorker.cancel(true);
        historyStatusLabel.setText("Searching history...");
        historySearchTimer.restart();
    }

    private void searchHistory() { loadHistoryPage(false); }

    private void loadHistoryPage(boolean append) {
        if(historySession==null) return;
        if(append && (historyPage==null || historyPage.next()==null)) return;
        long generation=++searchGeneration;
        if(searchWorker!=null) searchWorker.cancel(true);
        var session=historySession;var filter=currentHistoryFilter();
        var cursor=append ? historyPage.next() : null;
        int limit=append ? 100 : historyLimit;
        String notice=historySnapshot==null ? "" : historySnapshot.notice();
        historyLoadMore.setEnabled(false);
        searchWorker=new SwingWorker<TradeHistoryService.Page,Void>() {
            protected TradeHistoryService.Page doInBackground() throws Exception { return session.page(filter,limit,cursor,notice); }
            protected void done() {
                if(generation!=searchGeneration) return;
                try { acceptHistoryPage(get(),append,filter.query()); }
                catch(Exception failure) { historyStatusLabel.setText("Could not load history: "+errorMessage(failure)); }
                finally { historyLoadMore.setEnabled(true); }
            }
        };
        searchWorker.execute();
    }

    private void renderHistory() {
        TradeRecord selected=selectedRecord();
        String filter=historyQuery();
        if(!filter.equals(completedHistoryQuery)) return;
        var rows=new java.util.Vector<java.util.Vector<Object>>();
        renderingHistory=true;
        try {
            visibleRecords.clear();
            for(TradeRecord r:allRecords) {
                visibleRecords.add(r);
                rows.add(new java.util.Vector<>(List.of(r.date.format(HISTORY_DATE_FMT),r.customerName,r.paymentMethod,
                        String.format(java.util.Locale.ROOT,"$%.2f",r.totalValue),r.totalCards,r.inventoried)));
            }
            // One table event avoids sorting once per inserted receipt and preserves column widths.
            historyTableModel.getDataVector().clear();historyTableModel.getDataVector().addAll(rows);
            historyTableModel.fireTableDataChanged();
            historyTable.clearSelection();
            if(selected!=null) selectHistoryRecord(selected.historyKey());
        } finally { renderingHistory=false; }
        loadHistoryPreview();
        updateHistoryCount();
        updateHistoryStats();
    }

    private void updateHistoryStats() {
        if(historyPage==null) return;
        historyLoadMore.setVisible(historyMatchCount>visibleRecords.size() && historyPage.next()!=null);
        BigDecimal totalValue=historyPage.total();
        BigDecimal average=historyMatchCount==0 ? BigDecimal.ZERO : totalValue.divide(BigDecimal.valueOf(historyMatchCount),2,java.math.RoundingMode.HALF_UP);
        historyStatsTotalTrades.setText("Trades: "+historyMatchCount);
        historyStatsTotalCards.setText("  Cards: "+historyPage.cards());
        historyStatsTotalValue.setText(String.format(java.util.Locale.ROOT,"  Total: $%.2f",totalValue));
        historyStatsAvgValue.setText(String.format(java.util.Locale.ROOT,"  Avg: $%.2f",average));
    }

    private void updateHistoryCount() {
        if(!historyQuery().equals(completedHistoryQuery)) return;
        String notice=historySnapshot==null ? "" : historySnapshot.notice();
        historyStatusLabel.setText("Showing "+visibleRecords.size()+" of "+historyMatchCount+" trades"+(notice.isEmpty() ? "" : " | "+notice));
    }

    private String cachedReceipt(TradeRecord record) {
        return record==null || historySnapshot==null ? null : contentCache.get(historySnapshot.token(record));
    }

    /** Returns the TradeRecord for the currently selected history row, or null. */
    private TradeRecord selectedRecord() {
        int viewRow = historyTable.getSelectedRow();
        if (viewRow < 0) return null;
        int modelRow = historyTable.convertRowIndexToModel(viewRow);
        return modelRow < visibleRecords.size() ? visibleRecords.get(modelRow) : null;
    }

    private void loadHistoryPreview() {
        if(renderingHistory) return;
        TradeRecord record=selectedRecord();
        String token=record==null || historySnapshot==null ? null : historySnapshot.token(record);
        String content=cachedReceipt(record);
        updateReceiptActions(record,content!=null);
        if(token!=null && token.equals(previewToken) && content!=null) return;
        previewToken=token;
        long generation=++previewGeneration;
        if(previewWorker!=null) previewWorker.cancel(true);
        historyPreviewArea.setText(content!=null ? content : record==null ? "" : "Loading receipt...");
        historyPreviewArea.setCaretPosition(0);
        if(record==null || content!=null || historySession==null) return;
        var session=historySession;var snapshot=historySnapshot;
        previewWorker=new SwingWorker<String,Void>() {
            protected String doInBackground() throws Exception { return session.receipt(snapshot,record); }
            protected void done() {
                if(generation!=previewGeneration) return;
                try {
                    String body=get();contentCache.put(token,body);
                    historyPreviewArea.setText(body);historyPreviewArea.setCaretPosition(0);
                    updateReceiptActions(selectedRecord(),true);
                } catch(Exception failure) { historyPreviewArea.setText("Could not load receipt: "+errorMessage(failure)); }
            }
        };
        previewWorker.execute();
    }

    private void updateReceiptActions(TradeRecord record,boolean ready) {
        boolean busy=inventorySavePending || deletionPending;
        inventoriedCheck.setSelected(record!=null && record.inventoried);
        inventoriedCheck.setEnabled(record!=null && !busy);
        editTradeButton.setEnabled(record!=null && !busy && (record.tradeId!=null || ready));
        revisionHistoryButton.setEnabled(record!=null && record.tradeId!=null && !busy);
        deleteTradeButton.setEnabled(record!=null && !busy);
        histPrintBtn.setEnabled(record!=null && ready && !busy);histPdfBtn.setEnabled(record!=null && ready && !busy);
    }

    private void saveInventoryStatus() {
        TradeRecord record=selectedRecord();
        if (record==null || inventorySavePending) return;
        boolean value=inventoriedCheck.isSelected();
        inventorySavePending=true;
        inventoriedCheck.setEnabled(false);
        editTradeButton.setEnabled(false);
        new SwingWorker<Void,Void>() {
            protected Void doInBackground() throws Exception {
                TradeHistoryService.repository(com.cardpricer.util.AppDataDirectory.tradesPath()).setInventoried(record,value);
                return null;
            }
            protected void done() {
                inventorySavePending=false;
                try {
                    get();
                    historyGeneration++;
                    allRecords.replaceAll(r -> r.historyKey().equals(record.historyKey()) && r.revision==record.revision ? r.withInventoried(value) : r);
                    filterHistory(false);
                    selectHistoryRecord(record.historyKey());
                    historyStatusLabel.setText(value ? "Marked inventoried into POS" : "Marked as needing POS inventory");
                    inventorySyncLabel.setText("POS status: saved locally; syncing...");
                    com.cardpricer.service.InventoryStatusSyncService.requestSync();
                } catch (Exception failure) {
                    loadHistoryPreview();
                    JOptionPane.showMessageDialog(FileManagerPanel.this,"Could not save POS status: " + errorMessage(failure),"Save failed",JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void selectHistoryRecord(String key) {
        for (int i=0;i<visibleRecords.size();i++) if (visibleRecords.get(i).historyKey().equals(key)) {
            int view=historyTable.convertRowIndexToView(i);
            if (view>=0) historyTable.setRowSelectionInterval(view,view);
            return;
        }
        loadHistoryPreview();
    }

    private void editHistoryTrade() {
        TradeRecord record=selectedRecord();
        if (record==null) return;
        if (record.tradeId==null) { editLegacyReceipt(record); return; }
        editTradeButton.setEnabled(false);
        new SwingWorker<com.cardpricer.model.TradeDraft,Void>() {
            protected com.cardpricer.model.TradeDraft doInBackground() throws Exception {
                return TradeHistoryService.openForEditing(record,com.cardpricer.util.AppDataDirectory.tradesPath(),PreferencesPanel.getSharedTradesFolder());
            }
            protected void done() {
                loadHistoryPreview();
                try {
                    var draft=get();
                    if (tradeEditor==null) throw new IllegalStateException("The trade editor is unavailable");
                    tradeEditor.accept(draft);
                } catch (Exception failure) {
                    JOptionPane.showMessageDialog(FileManagerPanel.this,errorMessage(failure),"Could not open trade",JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private void editLegacyReceipt(TradeRecord record) {
        String body=cachedReceipt(record);
        if(body==null) return;
        JTextArea text=new JTextArea(body,24,70);
        text.setFont(new Font("Monospaced",Font.PLAIN,13));
        JPanel editor=new JPanel(new BorderLayout(8,8));
        editor.add(new JLabel("Older receipt: edit its text below. The original is backed up; POS exports are not recalculated."),BorderLayout.NORTH);
        editor.add(new JScrollPane(text),BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this,editor,"Edit saved receipt",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION) return;
        String content=text.getText();
        new SwingWorker<Void,Void>() {
            protected Void doInBackground() throws Exception {
                TradeHistoryService.saveLegacyReceipt(record,content,com.cardpricer.util.AppDataDirectory.tradesPath());return null;
            }
            protected void done() {
                try { get();refreshHistoryList(); }
                catch (Exception failure) { JOptionPane.showMessageDialog(FileManagerPanel.this,errorMessage(failure),"Could not save receipt",JOptionPane.ERROR_MESSAGE); }
            }
        }.execute();
    }

    private void showRevisionHistory() {
        TradeRecord record=selectedRecord();
        if (record==null || record.tradeId==null) return;
        revisionHistoryButton.setEnabled(false);
        new SwingWorker<String,Void>() {
            protected String doInBackground() throws Exception {
                String shared=PreferencesPanel.getSharedTradesFolder();
                var repo=TradeHistoryService.repository(com.cardpricer.util.AppDataDirectory.tradesPath());
                if (!shared.isBlank()) TradeHistoryService.openForEditing(record,com.cardpricer.util.AppDataDirectory.tradesPath(),shared);
                return com.cardpricer.service.SharedTradeService.revisionHistory(repo,shared.isBlank() ? null : Path.of(shared),record.tradeId);
            }
            protected void done() {
                loadHistoryPreview();
                try {
                    JTextArea text=new JTextArea(get(),24,85);
                    text.setEditable(false);text.setCaretPosition(0);
                    text.setLineWrap(true);text.setWrapStyleWord(true);
                    JOptionPane.showMessageDialog(FileManagerPanel.this,new JScrollPane(text),"Trade revision history",JOptionPane.PLAIN_MESSAGE);
                } catch (Exception failure) {
                    JOptionPane.showMessageDialog(FileManagerPanel.this,errorMessage(failure),"Could not load revision history",JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    private static String errorMessage(Throwable error) {
        while (error.getCause()!=null) error=error.getCause();
        return error.getMessage();
    }

    private void historyPrint() {
        TradeRecord r = selectedRecord();
        if (r == null) {
            JOptionPane.showMessageDialog(this, "Please select a record.", "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            String content = cachedReceipt(r);
            if(content==null) return;
            ReceiptPrintService.printReceipt(this, content);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not read file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void historySaveAsPdf() {
        TradeRecord r = selectedRecord();
        if (r == null) {
            JOptionPane.showMessageDialog(this, "Please select a record.", "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            String content = cachedReceipt(r);
            if(content==null) return;
            String pdfPath = r.filename.replace(".txt", ".pdf");
            ReceiptPrintService.saveAsPdf(this, content, pdfPath);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not read file: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void historyOpenInExplorer() {
        TradeRecord r = selectedRecord();
        if (r == null) {
            JOptionPane.showMessageDialog(this, "Please select a record.", "No Selection", JOptionPane.WARNING_MESSAGE);
            return;
        }
        File dir = new File(r.filename).getParentFile();
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(dir);
            }
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not open folder: " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void downloadSelected() {
        int[] selectedRows = fileTable.getSelectedRows();

        if (selectedRows.length == 0) {
            JOptionPane.showMessageDialog(this,
                    "Please select files to download",
                    "No Selection",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        // Choose destination folder
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose Download Location");

        int result = chooser.showSaveDialog(this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File destinationDir = chooser.getSelectedFile();
        int successCount = 0;
        int failCount = 0;

        for (int selectedRow : selectedRows) {
            try {
                int modelRow = fileTable.convertRowIndexToModel(selectedRow);
                String sourcePath = (String) tableModel.getValueAt(modelRow, 4);
                String filename = (String) tableModel.getValueAt(modelRow, 0);

                File sourceFile = new File(sourcePath);
                File destFile = new File(destinationDir, filename);

                Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                successCount++;
            } catch (IOException e) {
                failCount++;
                e.printStackTrace();
            }
        }

        String message = String.format(java.util.Locale.ROOT, "Download complete!\n\n" +
                        "Success: %d file(s)\n" +
                        "Failed: %d file(s)\n\n" +
                        "Location: %s",
                successCount, failCount, destinationDir.getAbsolutePath());

        JOptionPane.showMessageDialog(this,
                message,
                "Download Complete",
                JOptionPane.INFORMATION_MESSAGE);

        statusLabel.setText(String.format(java.util.Locale.ROOT, "Downloaded %d file(s)", successCount));
    }

    private void openSelectedFolder() {
        int selectedRow = fileTable.getSelectedRow();

        if (selectedRow < 0) {
            JOptionPane.showMessageDialog(this,
                    "Please select a file",
                    "No Selection",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        String filePath = (String) tableModel.getValueAt(fileTable.convertRowIndexToModel(selectedRow), 4);
        File file = new File(filePath);
        File parentDir = file.getParentFile();

        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(parentDir);
            } else {
                // Fallback for systems without Desktop support
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    Runtime.getRuntime().exec("explorer " + parentDir.getAbsolutePath());
                } else if (os.contains("mac")) {
                    Runtime.getRuntime().exec("open " + parentDir.getAbsolutePath());
                } else {
                    Runtime.getRuntime().exec("xdg-open " + parentDir.getAbsolutePath());
                }
            }
            statusLabel.setText("Opened folder: " + parentDir.getName());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this,
                    "Failed to open folder: " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private static TradeDeletionService deletionService(String shared) throws Exception {
        Path local=com.cardpricer.util.AppDataDirectory.trades().toPath();
        return new TradeDeletionService(TradeHistoryService.repository(local.toString()),local,
                shared==null || shared.isBlank() ? null : Path.of(shared));
    }

    private void deleteSelected() { deleteFiles(fileTable,tableModel); }

    private void deleteFiles(JTable table,DefaultTableModel model) {
        if(deletionPending || inventorySavePending) return;
        List<Path> paths=new ArrayList<>();
        for(int row:table.getSelectedRows()) paths.add(Path.of((String)model.getValueAt(table.convertRowIndexToModel(row),4)).toAbsolutePath().normalize());
        if(paths.isEmpty()) {
            JOptionPane.showMessageDialog(this,"Please select files to delete","No Selection",JOptionPane.WARNING_MESSAGE);return;
        }
        String shared=PreferencesPanel.getSharedTradesFolder();
        Path local=com.cardpricer.util.AppDataDirectory.trades().toPath().toAbsolutePath().normalize();
        Path sharedRoot=shared==null || shared.isBlank() ? null : Path.of(shared).toAbsolutePath().normalize();
        boolean includesTrades=paths.stream().anyMatch(p->p.getParent().equals(local) || p.getParent().equals(sharedRoot));
        String message="Delete "+paths.size()+" selected file(s)?\n\n"+(includesTrades
                ? "Selected trades, all their revisions, and their CSV/receipt files will be removed from History, this computer, and the shared folder.\nOther computers remove their copies when they sync. Offline deletions will be retried.\nOther selected exports are deleted locally.\n\n"
                : "")+"This cannot be undone.";
        if(JOptionPane.showConfirmDialog(this,message,"Confirm Delete",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION) return;
        beginDeletion();
        new SwingWorker<String,Void>() {
            protected String doInBackground() throws Exception {
                var service=deletionService(shared);
                int deleted=0,pending=0;List<String> failures=new ArrayList<>();
                for(Path path:paths) try {
                    if(path.getParent().equals(local) || path.getParent().equals(sharedRoot)) pending+=service.deleteFile(path).pending();
                    else Files.deleteIfExists(path);
                    deleted++;
                } catch(Exception failure) { failures.add(path.getFileName()+": "+errorMessage(failure)); }
                return "Deleted: "+deleted+" selection(s)."+(pending>0 ? " Cleanup or shared sync is pending; the app will retry." : "")
                        +(failures.isEmpty() ? "" : "\n\nCould not delete:\n"+String.join("\n",failures));
            }
            protected void done() { finishDeletion(this); }
        }.execute();
    }

    private void deleteHistoryTrade() {
        TradeRecord record=selectedRecord();
        if(record==null || deletionPending || inventorySavePending) return;
        String message="Delete the trade for "+record.customerName+" ("+record.date.format(HISTORY_DATE_FMT)+")?\n\n"
                +"This removes all revisions and CSV/receipt files from History, this computer, and the shared folder.\n"
                +"Other computers remove their copies when they sync. Offline deletions will be retried.\n\nThis cannot be undone.";
        if(JOptionPane.showConfirmDialog(this,message,"Delete trade everywhere",JOptionPane.YES_NO_OPTION,JOptionPane.WARNING_MESSAGE)!=JOptionPane.YES_OPTION) return;
        String shared=PreferencesPanel.getSharedTradesFolder();
        beginDeletion();
        new SwingWorker<String,Void>() {
            protected String doInBackground() throws Exception {
                var result=deletionService(shared).delete(record);
                return result.pending()==0 ? "Trade deleted." : "Trade deleted. Cleanup or shared sync is pending; the app will retry.";
            }
            protected void done() { finishDeletion(this); }
        }.execute();
    }

    private void beginDeletion() {
        deletionPending=true;historyGeneration++;previewGeneration++;searchGeneration++;
        fileListGeneration++;sharedFileListGeneration++;
        historySearchTimer.stop();
        if(previewWorker!=null) previewWorker.cancel(true);
        if(searchWorker!=null) searchWorker.cancel(true);
        updateReceiptActions(selectedRecord(),false);
        historyStatusLabel.setText("Deleting...");
    }

    private void finishDeletion(SwingWorker<String,Void> worker) {
        deletionPending=false;
        historyTable.clearSelection();contentCache.clear();historySnapshot=null;
        completedHistoryQuery=null;
        try { JOptionPane.showMessageDialog(this,worker.get(),"Delete complete",JOptionPane.INFORMATION_MESSAGE); }
        catch(Exception failure) { JOptionPane.showMessageDialog(this,errorMessage(failure),"Could not delete",JOptionPane.ERROR_MESSAGE); }
        refreshFileList();
        if(tabs.getSelectedIndex()==1) refreshSharedFileList();
        refreshHistoryList();
        com.cardpricer.service.InventoryStatusSyncService.requestSync();
    }
}
