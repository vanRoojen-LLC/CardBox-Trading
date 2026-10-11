package com.cardpricer.gui.panel.trade;
import com.cardpricer.model.*;
import javax.swing.JTextField;
import java.util.UUID;
/** Captures editable customer/payment fields on the EDT into the immutable draft. */
public final class TradeCustomerPresenter {
    private final JTextField trader,customer,identification,checkNumber;
    private final PaymentTypePanel payment;
    public TradeCustomerPresenter(JTextField trader,JTextField customer,JTextField identification,JTextField checkNumber,PaymentTypePanel payment) {
        this.trader=trader;this.customer=customer;this.identification=identification;this.checkNumber=checkNumber;this.payment=payment;
    }
    public TradeDraft capture(UUID id,long revision,Quote quote) {
        boolean partial = "Partial".equalsIgnoreCase(payment.getPaymentType());
        return new TradeDraft(id,revision,trader.getText().trim(),customer.getText().trim(),identification.getText().trim(),
                checkNumber.getText().trim(),payment.getPaymentType(),partial ? payment.getPartialCreditPayout() : java.math.BigDecimal.ZERO,
                partial ? payment.getPartialCheckPayout() : java.math.BigDecimal.ZERO,
                quote.lines(),quote.quotedAt().toString(),quote.rateRevision());
    }
    /** Read Swing fields now; materialize the detached lines on the save worker. */
    public java.util.function.Supplier<TradeDraft> captureDeferred(UUID id,long revision,
            java.util.List<TradeLineCapture> lines,String rateRevision) {
        var fields=capture(id,revision,new Quote(java.time.Instant.now(),rateRevision,java.util.List.of()));
        return () -> new TradeDraft(fields.id(),fields.revision(),fields.trader(),fields.customer(),fields.identification(),
                fields.checkNumber(),fields.payment(),fields.credit(),fields.check(),lines.stream().map(TradeLineCapture::encode).toList(),
                fields.quotedAt(),fields.rateRevision());
    }
}
