package com.cardpricer.gui.dialog;

import com.cardpricer.gui.CardImagePopup;
import com.cardpricer.model.Card;
import com.cardpricer.service.ScryfallApiService;
import com.cardpricer.service.CardSearchService;
import com.cardpricer.service.ScryfallCatalogService;
import com.cardpricer.util.VintageUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Modal dialog for searching Magic cards by name when the collector number is unknown.
 * Results use the local catalog first, with Scryfall fallback, and appear in a scrollable list
 * with card art previews on hover.
 */
public class CardSearchDialog extends JDialog {

    private final CardSearchPresenter presenter;

    private JTextField searchField;
    private JTextField setCodeField;
    private JList<CardSearchResult> resultList;
    private DefaultListModel<CardSearchResult> listModel;
    private JComboBox<String> finishCombo;
    private JButton selectButton;
    private JLabel statusLabel;

    private Card selectedCard;
    private String selectedFinish;

    private Timer searchTimer;
    private CardImagePopup imagePopup;

    /**
     * Creates the dialog.
     *
     * @param owner      parent window used for centering and modal blocking
     * @param apiService Scryfall service used to perform name searches
     */
    public CardSearchDialog(Window owner, ScryfallApiService apiService) {
        super(owner, "Search for Card", ModalityType.APPLICATION_MODAL);
        this.presenter = new CardSearchPresenter(apiService.interactiveSearch(ScryfallCatalogService.getInstance()));

        initializeUI();
        javax.swing.event.DocumentListener changed=new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { scheduleSearch(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { scheduleSearch(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { scheduleSearch(); }
        };
        searchField.getDocument().addDocumentListener(changed);
        setCodeField.getDocument().addDocumentListener(changed);
        getRootPane().registerKeyboardAction(e->dispose(),KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE,0),JComponent.WHEN_IN_FOCUSED_WINDOW);

        setSize(650, 500);
        setLocationRelativeTo(owner);
    }

    private void initializeUI() {
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(15, 15, 15, 15));

        // Top: Search input
        mainPanel.add(createSearchPanel(), BorderLayout.NORTH);

        // Center: Results list
        mainPanel.add(createResultsPanel(), BorderLayout.CENTER);

        // Bottom: Actions
        mainPanel.add(createActionPanel(), BorderLayout.SOUTH);

        setContentPane(mainPanel);
    }

    private JPanel createSearchPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));

        JLabel titleLabel = new JLabel("Search by Card Name");
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        panel.add(titleLabel, BorderLayout.NORTH);

        JPanel inputPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 0, 2, 5);
        gbc.anchor = GridBagConstraints.WEST;

        // Instruction label spanning full width
        gbc.gridx = 0; gbc.gridy = 0; gbc.gridwidth = 3;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        inputPanel.add(new JLabel("Type a card name or set and collector code:"), gbc);

        // Name field (takes remaining width)
        gbc.gridy = 1; gbc.gridwidth = 1; gbc.weightx = 1.0;
        searchField = new JTextField();
        searchField.setFont(searchField.getFont().deriveFont(14f));
        searchField.addActionListener(e -> performSearch(false));
        inputPanel.add(searchField, gbc);

        // Set label
        gbc.gridx = 1; gbc.weightx = 0; gbc.fill = GridBagConstraints.NONE;
        inputPanel.add(new JLabel("Set (optional):"), gbc);

        // Set field (fixed width)
        gbc.gridx = 2;
        setCodeField = new JTextField(10);
        setCodeField.setToolTipText("Enter set code or name — e.g. \"lea\", \"alpha\", \"arabian\"");
        setCodeField.addActionListener(e -> performSearch(false));
        // Resolve friendly name aliases visually when focus leaves the field
        setCodeField.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                String text = setCodeField.getText().trim();
                if (!text.isEmpty()) {
                    String resolved = VintageUtil.resolveSetAlias(text);
                    if (!resolved.equalsIgnoreCase(text)) {
                        setCodeField.setText(resolved.toUpperCase());
                    }
                }
            }
        });
        inputPanel.add(setCodeField, gbc);

        panel.add(inputPanel, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createResultsPanel() {
        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Search Results"));

        listModel = new DefaultListModel<>();
        resultList = new JList<>(listModel);
        resultList.setFont(resultList.getFont().deriveFont(13f));
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.setCellRenderer(new CardResultRenderer());

        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    selectAndClose();
                }
            }
        });

        resultList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    selectAndClose();
                }
            }
        });

        // Card image hover popup
        resultList.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                int idx = resultList.locationToIndex(e.getPoint());
                if (idx < 0 || idx >= listModel.getSize()) {
                    getImagePopup().hide();
                    return;
                }
                CardSearchResult result = listModel.getElementAt(idx);
                getImagePopup().show(result.card.getImageUrl(), e.getLocationOnScreen());
            }
        });
        resultList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) { getImagePopup().hide(); }
        });

        JScrollPane scrollPane = new JScrollPane(resultList);
        scrollPane.setPreferredSize(new Dimension(600, 300));

        panel.add(scrollPane, BorderLayout.CENTER);

        statusLabel = new JLabel("Enter a card name to search...");
        statusLabel.setBorder(new EmptyBorder(5, 5, 5, 5));
        panel.add(statusLabel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createActionPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 0));

        // Left: Finish selector
        JPanel finishPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        finishPanel.add(new JLabel("Finish:"));
        finishCombo = new JComboBox<>(new String[]{"Normal", "Foil", "Etched", "Surge Foil"});
        finishPanel.add(finishCombo);

        panel.add(finishPanel, BorderLayout.WEST);

        // Right: Buttons
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));

        selectButton = new JButton("Select Card");
        selectButton.putClientProperty("JButton.buttonType", "roundRect");
        selectButton.setEnabled(false);
        selectButton.addActionListener(e -> selectAndClose());

        JButton cancelButton = new JButton("Cancel");
        cancelButton.putClientProperty("JButton.buttonType", "roundRect");
        cancelButton.addActionListener(e -> dispose());

        JButton online=new JButton("Search online");
        online.setToolTipText("Search Scryfall for more printings or cards missing from the downloaded catalog");
        online.addActionListener(e->performSearch(true));
        buttonPanel.add(online);
        buttonPanel.add(selectButton);
        buttonPanel.add(cancelButton);

        panel.add(buttonPanel, BorderLayout.EAST);

        return panel;
    }

    public void setInitialSearch(String text) {
        searchField.setText(text==null ? "" : text);
        if (!searchField.getText().isBlank()) scheduleSearch();
    }

    private void scheduleSearch() {
        presenter.cancel();
        selectButton.setEnabled(false);resultList.setEnabled(false);
        if(imagePopup!=null) imagePopup.hide();
        if(searchTimer==null) {
            searchTimer=new Timer(CardSearchService.TYPING_DELAY_MS,e->performSearch(false));searchTimer.setRepeats(false);
        }
        searchTimer.stop();
        if(searchField.getText().trim().length()<2) {
            listModel.clear();statusLabel.setText("Enter at least 2 characters to search...");return;
        }
        statusLabel.setText("Searching...");
        searchTimer.setInitialDelay(CardSearchService.advanced(searchField.getText()) ? 400 : CardSearchService.TYPING_DELAY_MS);
        searchTimer.restart();
    }

    @Override public void dispose() {
        presenter.close();if(searchTimer!=null) searchTimer.stop();
        if(imagePopup!=null) imagePopup.dispose();super.dispose();
    }

    private void performSearch(boolean online) {
        if(searchTimer!=null) searchTimer.stop();
        String query=searchField.getText().trim();
        if(query.length()<2) { scheduleSearch();return; }
        selectButton.setEnabled(false);resultList.setEnabled(false);
        statusLabel.setText(online || CardSearchService.advanced(query) ? "Searching Scryfall..." : "Searching cards...");
        presenter.search(query,setCodeField.getText(),online,result -> {
            if(!isDisplayable()) return;
            var cards=result.cards().stream().map(card->new CardSearchResult(card.toCard())).toList();
            displayResults(cards);
            String source=result.online() ? "Scryfall" : "Downloaded catalog";
            statusLabel.setText(cards.isEmpty() ? "No cards found matching \""+query+"\""
                    : source+" · "+cards.size()+" of "+result.total()+" printings"+(result.total()>cards.size() ? " · Narrow the name or set" : ""));
            if(result.suggestedFinish()!=null) finishCombo.setSelectedItem(switch(result.suggestedFinish()) {
                case "F"->"Foil";case "E"->"Etched";case "S"->"Surge Foil";default->"Normal";
            });
        },failure -> {
            if(!isDisplayable()) return;
            listModel.clear();selectButton.setEnabled(false);
            statusLabel.setText("Search failed: "+failure.getMessage());
        });
    }

    private void displayResults(List<CardSearchResult> results) {
        listModel.clear();

        if (results.isEmpty()) {
            statusLabel.setText("No cards found matching \"" + searchField.getText() + "\"");
            return;
        }

        listModel.addAll(results);
        resultList.setEnabled(true);

        statusLabel.setText("Found " + results.size() + " card(s)");
        resultList.setSelectedIndex(0);
        selectButton.setEnabled(true);
    }

    private void selectAndClose() {
        if(!selectButton.isEnabled()) return;
        CardSearchResult selected = resultList.getSelectedValue();
        if (selected != null) {
            selectedCard = selected.card;

            String finish = (String) finishCombo.getSelectedItem();
            if ("Foil".equals(finish)) {
                selectedFinish = "F";
            } else if ("Etched".equals(finish)) {
                selectedFinish = "E";
            } else if ("Surge Foil".equals(finish)) {
                selectedFinish = "S";
            } else {
                selectedFinish = "";
            }

            dispose();
        }
    }

    /**
     * Returns the card chosen by the user, or {@code null} if the dialog was
     * cancelled or closed without a selection.
     */
    public Card getSelectedCard() {
        return selectedCard;
    }

    /**
     * Returns the finish chosen in the finish combo box: {@code ""} (Normal),
     * {@code "F"} (Foil), or {@code "E"} (Etched).  Returns {@code null} if no
     * card was selected.
     */
    public String getSelectedFinish() {
        return selectedFinish;
    }

    private CardImagePopup getImagePopup() {
        if (imagePopup == null) {
            imagePopup = new CardImagePopup(this);
        }
        return imagePopup;
    }

    /**
     * Inner class to hold search result data
     */
    private static class CardSearchResult {
        final Card card;

        CardSearchResult(Card card) {
            this.card = card;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(card.getName());
            sb.append(" [").append(card.getSetCode()).append(" ").append(card.getCollectorNumber()).append("]");

            if (card.getFrameEffectDisplay() != null) {
                sb.append(" - ").append(card.getFrameEffectDisplay());
            }

            if (card.getRarity() != null) {
                String r = card.getRarity();
                sb.append(" (").append(r.substring(0, 1).toUpperCase()).append(r.substring(1)).append(")");
            }

            sb.append(" - ");
            if (card.hasNormalPrice()) {
                sb.append("$").append(card.getPrice());
            } else {
                sb.append("No price");
            }

            if (card.hasFoilPrice()) {
                sb.append(" / Foil $").append(card.getFoilPrice());
            }

            return sb.toString();
        }
    }

    /**
     * Custom cell renderer for better-looking results
     */
    private static class CardResultRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                                                      int index, boolean isSelected, boolean cellHasFocus) {

            JLabel label = (JLabel) super.getListCellRendererComponent(
                    list, value, index, isSelected, cellHasFocus);

            if (value instanceof CardSearchResult) {
                CardSearchResult result = (CardSearchResult) value;
                Card card = result.card;

                // Build HTML for better formatting
                StringBuilder html = new StringBuilder("<html>");
                html.append("<b>").append(escapeHtml(card.getName())).append("</b>");
                html.append(" <span style='color:gray;'>[")
                        .append(card.getSetCode()).append(" ")
                        .append(card.getCollectorNumber())
                        .append("]</span>");

                if (card.getFrameEffectDisplay() != null) {
                    html.append(" <i>").append(card.getFrameEffectDisplay()).append("</i>");
                }

                if (card.getRarity() != null) {
                    String rarity = card.getRarity();
                    String rarityColor = rarityColor(rarity);
                    String rarityLabel = rarity.substring(0, 1).toUpperCase() + rarity.substring(1);
                    html.append(" <b style='color:").append(rarityColor).append(";'>")
                            .append(rarityLabel).append("</b>");
                }

                if (card.isReserved()) {
                    html.append(" <b style='color:#CC7700;'>[RL]</b>");
                }

                html.append("<br><span style='font-size:11px;'>");
                if (card.hasNormalPrice()) {
                    html.append("Normal: $").append(card.getPrice());
                }
                if (card.hasFoilPrice()) {
                    if (card.hasNormalPrice()) html.append(" | ");
                    html.append("Foil: $").append(card.getFoilPrice());
                }
                html.append("</span></html>");

                label.setText(html.toString());
                label.setBorder(new EmptyBorder(5, 5, 5, 5));
            }

            return label;
        }

        private String escapeHtml(String text) {
            return text.replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;");
        }

        private String rarityColor(String rarity) {
            if (rarity == null) return "gray";
            switch (rarity.toLowerCase()) {
                case "mythic":   return "#E36D17";
                case "rare":     return "#A58E4A";
                case "uncommon": return "#7A9EB5";
                default:         return "gray";
            }
        }
    }
}
