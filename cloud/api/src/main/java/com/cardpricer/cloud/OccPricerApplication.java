package com.cardpricer.cloud;

import com.cardpricer.cloud.catalog.CatalogImporter;
import com.cardpricer.cloud.catalog.PriceHistory;
import com.cardpricer.cloud.catalog.SwuCatalogImporter;
import com.cardpricer.cloud.catalog.SwuTcgplayerPrices;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class OccPricerApplication {
    public static void main(String[] args) {
        if (Arrays.asList(args).contains("import-catalog")) {
            // Run as the nightly Container Apps job: import, then exit with a status code.
            var app = new SpringApplication(OccPricerApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            var context = app.run(args);
            int status = 0;
            String file = context.getEnvironment().getProperty("app.catalog.file", "");
            try {
                var importer = context.getBean(CatalogImporter.class);
                if (file.isBlank()) importer.importFromScryfall();
                else importer.importFile(java.nio.file.Path.of(file));
            } catch (Exception e) {
                e.printStackTrace();
                status = 1;
            }
            // Star Wars: Unlimited runs even when Magic failed; either failure fails the job so it is noticed.
            if (file.isBlank()) {
                try {
                    context.getBean(SwuCatalogImporter.class).importFromSwuDb();
                } catch (Exception e) {
                    e.printStackTrace();
                    status = 1;
                }
                // TCGplayer's prices from TCGCSV, matched through the product ids swu-db just loaded.
                try {
                    context.getBean(SwuTcgplayerPrices.class).importFromTcgcsv();
                } catch (Exception e) {
                    e.printStackTrace();
                    status = 1;
                }
                // Whatever refreshed tonight goes into the price history, even if another import failed.
                try {
                    context.getBean(PriceHistory.class).record();
                } catch (Exception e) {
                    e.printStackTrace();
                    status = 1;
                }
            }
            System.exit(SpringApplication.exit(context, () -> 0) + status);
        }
        SpringApplication.run(OccPricerApplication.class, args);
    }
}
