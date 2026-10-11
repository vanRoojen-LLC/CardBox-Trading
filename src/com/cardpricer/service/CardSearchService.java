package com.cardpricer.service;

import com.cardpricer.exception.ScryfallApiException;
import com.cardpricer.model.*;
import com.cardpricer.util.CardCodeParser;
import com.cardpricer.util.VintageUtil;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Local names/codes first, with bounded cached provider fallbacks. No Swing or mutable cached cards. */
public final class CardSearchService {
    public static final int TYPING_DELAY_MS=125;
    public record Result(List<ProviderPrinting> cards,int total,boolean online,String suggestedFinish) {
        public Result { cards=List.copyOf(cards); }
    }
    private record Query(String text,String set,long generation,boolean online) {}
    private record Code(String set,String number,long generation) {}
    private static final Pattern ADVANCED=Pattern.compile("(?:^|\\s|\\()[!-]?[\\w]+\\s*[:<>=]|\\b(?:OR|AND|NOT)\\b|^[!/\"]");
    private final CardRepository catalog;
    private final ScryfallApiService api;
    private final RecentSearches<Query,Result> searches=new RecentSearches<>(64,Duration.ofMinutes(5));
    private final RecentSearches<Code,ProviderPrinting> lookups=new RecentSearches<>(128,Duration.ofMinutes(5));

    public CardSearchService(CardRepository catalog,ScryfallApiService api) { this.catalog=catalog;this.api=api; }
    boolean serves(CardRepository repository) { return catalog==repository; }
    public static boolean advanced(String query) { return ADVANCED.matcher(query).find(); }

    public CompletableFuture<Result> search(String text,String set,boolean online) {
        String query=text.trim(),filter=VintageUtil.resolveSetAlias(set.trim()).toLowerCase(Locale.ROOT);
        if(query.codePointCount(0,query.length())<2) return CompletableFuture.completedFuture(new Result(List.of(),0,false,null));
        boolean remote=online || advanced(query);
        var key=new Query(query,filter,catalog.generation(),remote);
        return searches.get(key,()-> {
            if(remote) return SearchTasks.online(()->online(query,filter));
            return SearchTasks.local(()->local(query,filter)).thenCompose(result -> result.total()>0
                    ? CompletableFuture.completedFuture(result) : SearchTasks.online(()->online(query,filter)));
        });
    }

    private Result local(String query,String set) {
        var code=code(query,set);
        if(code!=null && (set.isEmpty() || set.equalsIgnoreCase(code.setCode))) {
            var exact=catalog.lookup(code.setCode,code.collectorNumber);
            if(exact.isPresent()) return new Result(List.of(ProviderPrinting.from(exact.get())),1,false,code.finish);
        }
        if(catalog instanceof SearchableCardRepository names && names.isLoaded()) {
            var found=names.searchNames(query,set,50);
            return new Result(found.cards(),found.total(),false,null);
        }
        return new Result(List.of(),0,false,null);
    }

    public CompletableFuture<Card> lookup(ParsedCode parsed) {
        // Capture mutable ParsedCode fields before dispatching.
        var code=new Code(VintageUtil.resolveSetAlias(parsed.setCode).toLowerCase(Locale.ROOT),parsed.collectorNumber,catalog.generation());
        String finish=parsed.finish;
        return SearchTasks.local(()->catalog.lookup(code.set(),code.number())).thenCompose(found -> {
            if(found.isPresent() && hasPrice(found.get(),finish)) return CompletableFuture.completedFuture(found.get().copy());
            return lookups.get(code,()->SearchTasks.online(()-> {
                try { return ProviderPrinting.from(api.fetchCard(code.set(),code.number())); }
                catch(ScryfallApiException failure) { throw new CompletionException(failure); }
            })).thenApply(ProviderPrinting::toCard);
        });
    }
    public static boolean hasPrice(Card card,String finish) {
        return switch(finish) { case "E"->card.hasEtchedPrice();case "F","S"->card.hasFoilPrice();default->card.hasNormalPrice(); };
    }

    private Result online(String query,String set) {
        try {
            var code=advanced(query) ? null : code(query,set);
            if(code!=null && (set.isEmpty() || set.equalsIgnoreCase(code.setCode))) {
                try { return new Result(List.of(ProviderPrinting.from(api.fetchCard(code.setCode,code.collectorNumber))),1,true,code.finish); }
                catch(ScryfallApiException failure) { if(failure.statusCode()!=404) throw failure; }
            }
            try { return queryOnline(query,set); }
            catch(ScryfallApiException failure) {
                if(failure.statusCode()!=404) throw failure;
                if(advanced(query)) return new Result(List.of(),0,true,null);
                try {
                    var found=api.parseCardFromJson(api.makeApiCall("https://api.scryfall.com/cards/named?fuzzy="+encode(query)));
                    // The fuzzy endpoint picks one printing. Resolve the name again if a set was requested.
                    if(!set.isEmpty()) return queryOnline("!\""+found.getName().replace("\\","\\\\").replace("\"","\\\"")+"\"",set);
                    return new Result(List.of(ProviderPrinting.from(found)),1,true,null);
                } catch(ScryfallApiException fuzzyFailure) {
                    if(fuzzyFailure.statusCode()==404) return new Result(List.of(),0,true,null);
                    throw fuzzyFailure;
                }
            }
        } catch(ScryfallApiException failure) { throw new CompletionException(failure); }
    }
    private Result queryOnline(String query,String set) throws ScryfallApiException {
        String expression=set.isEmpty() ? query : "("+query+") set:"+set;
        var response=api.makeApiCall("https://api.scryfall.com/cards/search?q="+encode(expression)+"&unique=prints");
        var data=response.optJSONArray("data");var cards=new ArrayList<ProviderPrinting>();
        if(data!=null) for(int i=0;i<Math.min(50,data.length());i++) cards.add(ProviderPrinting.from(api.parseCardFromJson(data.getJSONObject(i))));
        return new Result(cards,response.optInt("total_cards",cards.size()),true,null);
    }
    private static String encode(String text) { return URLEncoder.encode(text,StandardCharsets.UTF_8); }
    private static ParsedCode code(String text,String set) {
        if(!set.isEmpty() && text.matches("(?i)\\d+[a-z0-9★†.*-]*(?: [fes])?")) return CardCodeParser.parse(set+" "+text);
        return CardCodeParser.parse(text);
    }
}
