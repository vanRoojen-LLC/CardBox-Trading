package com.cardpricer.cloud.clubsync;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Reading Cost Management's query answers, and saying why when there is no figure. */
class AzureCostTest {
    @Test
    void readsTheMonthToDateTotal() {
        var r = AzureCost.parse("""
                {"properties":{"columns":[{"name":"Cost","type":"Number"},{"name":"Currency","type":"String"}],
                 "rows":[[123.456789,"USD"]]}}""");
        assertEquals(123.46, r.usd());
        assertNull(r.error());
    }

    @Test
    void columnsAreFoundByNameNotPosition() {
        var r = AzureCost.parse("""
                {"properties":{"columns":[{"name":"Currency","type":"String"},{"name":"totalCost","type":"Number"}],
                 "rows":[["usd",7]]}}""");
        assertEquals(7.0, r.usd());
    }

    @Test
    void nothingBilledYetIsZero() {
        var r = AzureCost.parse("""
                {"properties":{"columns":[{"name":"Cost","type":"Number"},{"name":"Currency","type":"String"}],"rows":[]}}""");
        assertEquals(0.0, r.usd());
        assertNull(r.error());
    }

    @Test
    void otherCurrenciesAndOddAnswersGiveAReasonInstead() {
        var eur = AzureCost.parse("""
                {"properties":{"columns":[{"name":"Cost"},{"name":"Currency"}],"rows":[[5,"EUR"]]}}""");
        assertNull(eur.usd());
        assertEquals("Billed in EUR, not USD", eur.error());
        assertEquals("Unreadable cost response", AzureCost.parse("<html>").error());
        assertEquals("Unexpected cost response", AzureCost.parse("{\"error\":{}}").error());
        assertEquals("Cost response has no Cost column", AzureCost.parse("""
                {"properties":{"columns":[{"name":"Currency"}],"rows":[]}}""").error());
    }

    @Test
    void missingSettingsAreNamed() {
        var r = new AzureCost("", "rg", "", "", "", "https://management.azure.com").monthToDate();
        assertNull(r.usd());
        assertEquals("Not configured: AZURE_SUBSCRIPTION_ID, AZURE_CLIENT_ID, managed identity endpoint", r.error());
    }
}
