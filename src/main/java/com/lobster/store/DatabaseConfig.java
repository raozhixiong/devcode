package com.lobster.store;

import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.sqlite.SQLiteDataSource;
import com.lobster.util.StateDirs;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Configuration
public class DatabaseConfig {

    @Bean
    public Path stateDir(@Value("${lobster.state-dir:}") String override) {
        return StateDirs.resolve(override);
    }

    @Bean
    public DataSource sharedDataSource(Path stateDir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + stateDir.resolve("lobster.db"));
        ds.setConfig(toPragmas());
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration/shared")
            .baselineOnMigrate(true)
            .load()
            .migrate();
        return ds;
    }

    private static org.sqlite.SQLiteConfig toPragmas() {
        org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
        cfg.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
        cfg.setBusyTimeout(5000);
        cfg.enforceForeignKeys(true);
        return cfg;
    }
}
