package com.cardpricer.gui.panel.trade;

import com.cardpricer.model.*;
import com.cardpricer.service.BuyRateService;
import com.cardpricer.service.PricingService;
import javax.swing.table.DefaultTableModel;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** One typed row owns quantity, condition, valuation and payout. List APIs are read views. */
public final class TradeTableModel extends DefaultTableModel {
    private static final String[] COLUMNS = {"", "Code", "Card Name", "Condition", "Qty", "Unit Price", "Total", "Rate"};
    private final List<Row> rows = new ArrayList<>();
    private final PricingService pricing = new PricingService();
    private final Set<Row> dirty = new LinkedHashSet<>();
    private BigDecimal market=BigDecimal.ZERO, credit=BigDecimal.ZERO, check=BigDecimal.ZERO;
    private int quantity;
    public record Summary(BigDecimal market, BigDecimal credit, BigDecimal check, int quantity) {}
    private static final class Row {
        final TradeItem item;
        Condition condition;
        BigDecimal value;
        boolean selected;
        String rate = "";
        BuyRateService.PayoutResult payout;
        BigDecimal marketTotal=BigDecimal.ZERO, creditTotal=BigDecimal.ZERO, checkTotal=BigDecimal.ZERO;
        int countedQuantity;
        TradeLineCapture capture;
        Row(TradeItem item, Condition condition, BigDecimal value) { this.item=item; this.condition=condition; this.value=value; }
    }
    public void addTrade(TradeItem item, String condition, BigDecimal value) {
        Row row = new Row(item, Condition.valueOf(condition), money(value));
        rows.add(row); dirty.add(row); fireTableRowsInserted(rows.size()-1, rows.size()-1);
    }
    public void invalidatePayouts() { dirty.addAll(rows); }
    public Summary summary(java.util.function.BiFunction<TradeItem,BigDecimal,BuyRateService.PayoutResult> rates) {
        for(var row:dirty) {
            subtract(row);
            row.payout=rates.apply(row.item,row.value);
            row.countedQuantity=row.item.getQuantity();
            var count=BigDecimal.valueOf(row.countedQuantity);
            row.marketTotal=row.value.multiply(count);
            row.creditTotal=row.payout.creditPayout().multiply(count);
            row.checkTotal=row.payout.checkPayout().multiply(count);
            market=market.add(row.marketTotal); credit=credit.add(row.creditTotal); check=check.add(row.checkTotal);
            quantity+=row.countedQuantity;
            row.rate=String.format(Locale.ROOT,"%.0f%% / %.0f%%%s",row.payout.appliedCreditRate().multiply(BigDecimal.valueOf(100)),
                    row.payout.appliedCheckRate().multiply(BigDecimal.valueOf(100)),row.payout.isBounty() ? " ★" : "");
            row.capture=null;
        }
        dirty.clear();
        return new Summary(market,credit.setScale(2,RoundingMode.HALF_UP),check.setScale(2,RoundingMode.HALF_UP),quantity);
    }
    /** Captures references to unchanged immutable rows; only edited rows need copying. */
    public List<TradeLineCapture> captureLines(Map<UUID,TradeLine> saved) {
        var result=new ArrayList<TradeLineCapture>(rows.size());
        for(var row:rows) {
            if(row.capture==null) row.capture=new TradeLineCapture(row.item,row.condition,row.value,row.payout,saved.get(row.item.getLineId()));
            result.add(row.capture);
        }
        return List.copyOf(result);
    }
    private void subtract(Row row) {
        market=market.subtract(row.marketTotal); credit=credit.subtract(row.creditTotal); check=check.subtract(row.checkTotal);
        quantity-=row.countedQuantity;
    }
    public BigDecimal priceAt(int row) { return rows.get(row).value; }
    public BigDecimal totalAt(int row) { return priceAt(row).multiply(BigDecimal.valueOf(rows.get(row).item.getQuantity())); }
    public UUID idAt(int row) { return rows.get(row).item.getLineId(); }
    public int indexOf(UUID id) {
        for (int i=0;i<rows.size();i++) if (idAt(i).equals(id)) return i;
        return -1;
    }
    public List<TradeItem> items() { return new AbstractList<>() {
        public TradeItem get(int i) { return rows.get(i).item; } public int size() { return rows.size(); }
    }; }
    public List<String> conditions() { return new AbstractList<>() {
        public String get(int i) { return rows.get(i).condition.name(); } public int size() { return rows.size(); }
        public String set(int i, String condition) { String old=get(i); rows.get(i).condition=Condition.valueOf(condition); dirty.add(rows.get(i)); return old; }
    }; }
    public List<BuyRateService.PayoutResult> payouts() { return new AbstractList<>() {
        public BuyRateService.PayoutResult get(int i) { return rows.get(i).payout; } public int size() { return rows.size(); }
        public BuyRateService.PayoutResult set(int i, BuyRateService.PayoutResult value) {
            var old=get(i); rows.get(i).payout=value; return old;
        }
    }; }
    @Override public int getRowCount() { return rows == null ? 0 : rows.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int column) { return COLUMNS[column]; }
    @Override public Class<?> getColumnClass(int column) {
        return switch(column) { case 0 -> Boolean.class; case 4 -> Integer.class; default -> String.class; };
    }
    @Override public boolean isCellEditable(int row, int column) { return column==0 || column==3 || column==4 || column==5; }
    @Override public Object getValueAt(int index, int column) {
        Row row=rows.get(index);
        return switch(column) {
            case 0 -> row.selected;
            case 1 -> row.item.getSetCollectorCode();
            case 2 -> row.item.getCard().getName() + (row.item.isFoil() ? " ("+row.item.getFinish()+")" : "");
            case 3 -> row.condition.name();
            case 4 -> row.item.getQuantity();
            case 5 -> "$"+row.value.toPlainString();
            case 6 -> "$"+totalAt(index).toPlainString();
            case 7 -> row.rate;
            default -> throw new IndexOutOfBoundsException(column);
        };
    }
    @Override public void setValueAt(Object value, int index, int column) {
        Row row=rows.get(index);
        switch(column) {
            case 0 -> row.selected=Boolean.TRUE.equals(value);
            case 3 -> {
                row.condition=Condition.valueOf(value.toString());
                BigDecimal base=pricing.applyPricingRules(row.item.getUnitPrice(), row.item.getCard().getRarity());
                row.value=money(pricing.applyConditionMultiplier(base,row.condition.name()));
                if (row.condition.name().equals(row.item.getManualCondition())) row.value=row.item.getManualPrice();
            }
            case 4 -> row.item.setQuantity(Integer.parseInt(value.toString()));
            case 5 -> {
                row.value=money(new BigDecimal(value.toString().replace("$", "").trim()));
                row.item.setManualOverride(row.condition.name(),row.value);
            }
            case 6 -> { return; } // Derived total has no independent state.
            case 7 -> row.rate=Objects.toString(value, "");
            default -> throw new IllegalArgumentException("Read-only column");
        }
        if(column==3 || column==4 || column==5) dirty.add(row);
        fireTableCellUpdated(index,column);
        if (column==3 || column==4 || column==5) {
            fireTableCellUpdated(index,6);
        }
    }
    @Override public void removeRow(int index) { var row=rows.remove(index); dirty.remove(row); subtract(row); fireTableRowsDeleted(index,index); }
    @Override public void setRowCount(int count) {
        if (rows==null) return;
        if (count!=0) throw new IllegalArgumentException("Use addTrade");
        rows.clear(); dirty.clear(); market=credit=check=BigDecimal.ZERO; quantity=0; fireTableDataChanged();
    }
    private static BigDecimal money(BigDecimal value) {
        if (value.signum()<0) throw new IllegalArgumentException("Price must be non-negative");
        return value.setScale(2,RoundingMode.UNNECESSARY);
    }
}
