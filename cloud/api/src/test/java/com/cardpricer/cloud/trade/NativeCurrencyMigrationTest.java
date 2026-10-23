package com.cardpricer.cloud.trade;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class NativeCurrencyMigrationTest {
    @Test void additiveMigrationKeepsPriorTransactionsAndOldInsertShapeUnknown() {
        try (var pg = new PostgreSQLContainer<>("postgres:17-alpine")) {
            pg.start();
            var source=new DriverManagerDataSource(pg.getJdbcUrl(),pg.getUsername(),pg.getPassword());
            Flyway.configure().dataSource(source).target("34").load().migrate();
            var jdbc=new JdbcTemplate(source);
            UUID tenant=UUID.randomUUID(),user=UUID.randomUUID(),location=UUID.randomUUID(),trade=UUID.randomUUID();
            jdbc.update("INSERT INTO tenants(id,name,trial_ends_at) VALUES (?,'Legacy',now())",tenant);
            jdbc.update("INSERT INTO users(id,tenant_id,email,name,role) VALUES (?,?,'legacy@example.test','Legacy','owner')",user,tenant);
            jdbc.update("INSERT INTO locations(id,tenant_id,name) VALUES (?,?,'Main')",location,tenant);
            String insert="INSERT INTO trades(id,tenant_id,number,created_by,payment,credit_total,check_total,market_total,location_id) VALUES (?,?,?,?,'credit',12.50,0.00,25.00,?)";
            jdbc.update(insert,trade,tenant,1,user,location);
            var before=jdbc.queryForMap("SELECT credit_total,check_total,market_total,created_at FROM trades WHERE id=?",trade);
            Flyway.configure().dataSource(source).load().migrate();
            assertEquals(before,jdbc.queryForMap("SELECT credit_total,check_total,market_total,created_at FROM trades WHERE id=?",trade));
            assertNull(jdbc.queryForObject("SELECT actual_payment_currency FROM trades WHERE id=?",String.class,trade));
            UUID oldWriter=UUID.randomUUID();jdbc.update(insert,oldWriter,tenant,2,user,location);
            assertNull(jdbc.queryForObject("SELECT actual_currency_confirmed_at FROM trades WHERE id=?",java.sql.Timestamp.class,oldWriter));
            assertThrows(org.springframework.dao.DataIntegrityViolationException.class,()->jdbc.update("UPDATE trades SET actual_payment_currency='EUR' WHERE id=?",oldWriter));
        }
    }
}
