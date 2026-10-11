package com.cardpricer.service;

public interface SearchableCardRepository extends CardRepository {
    boolean isLoaded();
    CardNameIndex.Matches searchNames(String query,String set,int limit);
}
