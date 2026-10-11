package com.cardpricer.service;

import com.cardpricer.model.ProviderPrinting;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Immutable name postings share provider records with the exact-code catalog. */
public final class CardNameIndex {
    public record Matches(List<ProviderPrinting> cards,int total) {
        public Matches { cards=List.copyOf(cards); }
    }
    private record Name(String normalized,List<ProviderPrinting> cards) {}
    private static final Pattern MARKS=Pattern.compile("\\p{M}+"), SEPARATORS=Pattern.compile("[^\\p{L}\\p{N}]+");
    private final List<Name> names;
    private final Map<String,int[]> postings;

    public CardNameIndex(Collection<ProviderPrinting> cards) {
        var grouped=new TreeMap<String,List<ProviderPrinting>>();
        for(var card:cards) grouped.computeIfAbsent(normalize(card.name()),ignored->new ArrayList<>()).add(card);
        var entries=new ArrayList<Name>(grouped.size());var building=new HashMap<String,Ints>();
        var order=Comparator.comparing((ProviderPrinting c)->c.identity().set()).thenComparing(c->c.identity().collectorNumber())
                .thenComparing(c->c.identity().providerId());
        for(var entry:grouped.entrySet()) {
            entry.getValue().sort(order);int id=entries.size();
            entries.add(new Name(entry.getKey(),List.copyOf(entry.getValue())));
            var grams=new HashSet<String>();
            for(int length=2;length<=3;length++) for(int i=0;i+length<=entry.getKey().length();i++) grams.add(entry.getKey().substring(i,i+length));
            for(String gram:grams) building.computeIfAbsent(gram,ignored->new Ints()).add(id);
        }
        var packed=new HashMap<String,int[]>(building.size());building.forEach((gram,ids)->packed.put(gram,ids.array()));
        names=List.copyOf(entries);postings=Map.copyOf(packed);
    }

    public Matches search(String text,String set,int limit) {
        if(limit<1 || limit>200) throw new IllegalArgumentException("Invalid card search limit");
        String query=normalize(text);if(query.isEmpty()) return new Matches(List.of(),0);
        String[] terms=query.split(" ");int[] candidates=null;
        for(String term:terms) {
            int length=Math.min(3,term.length());if(length<2) continue;
            for(int i=0;i+length<=term.length();i++) {
                int[] ids=postings.get(term.substring(i,i+length));
                if(ids==null) return new Matches(List.of(),0);
                if(candidates==null || ids.length<candidates.length) candidates=ids;
            }
        }
        // Single-letter terms are allowed alongside a longer term, but never scan the full catalog for one letter.
        if(candidates==null) return new Matches(List.of(),0);
        var matched=new ArrayList<Name>();
        for(int id:candidates) {
            if(Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            var name=names.get(id);boolean matches=true;
            for(String term:terms) if(!name.normalized().contains(term)) { matches=false;break; }
            if(matches) matched.add(name);
        }
        matched.sort(Comparator.comparingInt((Name n)->rank(n.normalized(),query,terms)).thenComparing(Name::normalized));
        var results=new ArrayList<ProviderPrinting>(limit);int total=0;
        for(var name:matched) for(var card:name.cards()) if(set.isEmpty() || set.equalsIgnoreCase(card.identity().set())) {
            total++;if(results.size()<limit) results.add(card);
        }
        return new Matches(results,total);
    }
    private static int rank(String name,String query,String[] terms) {
        if(name.equals(query)) return 0;
        if(name.startsWith(query)) return 1;
        for(String term:terms) if(!name.startsWith(term) && !name.contains(" "+term)) return 3;
        return 2;
    }
    public static String normalize(String value) {
        String text=MARKS.matcher(Normalizer.normalize(value,Normalizer.Form.NFKD)).replaceAll("").toLowerCase(Locale.ROOT);
        return SEPARATORS.matcher(text.replace("'","").replace("’","")).replaceAll(" ").trim();
    }
    private static final class Ints {
        private int[] values=new int[4];private int size;
        void add(int value) { if(size==values.length) values=Arrays.copyOf(values,size*2);values[size++]=value; }
        int[] array() { return Arrays.copyOf(values,size); }
    }
}
