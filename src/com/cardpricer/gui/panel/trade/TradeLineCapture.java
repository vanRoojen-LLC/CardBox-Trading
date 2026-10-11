package com.cardpricer.gui.panel.trade;

import com.cardpricer.model.*;
import com.cardpricer.service.BuyRateService;
import com.cardpricer.service.ProviderCardMapper;
import java.math.BigDecimal;
import java.util.UUID;

/** Detached editor state. Provider encoding and validation can run on the save worker. */
public final class TradeLineCapture {
    private final UUID id;
    private final Card card;
    private final String savedJson;
    private final Finish finish;
    private final Condition condition;
    private final int quantity;
    private final BigDecimal base, value, creditRate, checkRate, overrideValue;
    private final String overrideCondition;
    private volatile TradeLine encoded;

    TradeLineCapture(TradeItem item, Condition condition, BigDecimal value,
                     BuyRateService.PayoutResult payout, TradeLine saved) {
        id=item.getLineId(); card=item.getCard().copy(); savedJson=saved==null ? null : saved.cardJson();
        finish=Finish.fromCode(item.getFinishType()); this.condition=condition; quantity=item.getQuantity();
        base=item.getUnitPrice(); this.value=value;
        creditRate=payout.appliedCreditRate(); checkRate=payout.appliedCheckRate();
        overrideCondition=item.getManualCondition(); overrideValue=item.getManualPrice();
    }

    public TradeLine encode() {
        var result=encoded;
        if(result==null) {
            result=new TradeLine(id,card.identity(),savedJson==null ? ProviderCardMapper.toJson(card).toString() : savedJson,
                    finish,condition,quantity,base,value,creditRate,checkRate,overrideCondition,overrideValue);
            encoded=result;
        }
        return result;
    }
}
