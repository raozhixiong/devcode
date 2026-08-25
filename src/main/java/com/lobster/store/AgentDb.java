package com.lobster.store;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;

/** 每 agent 独立 SQLite 库；open 时自动建目录并执行 Flyway 迁移。 */
public final class AgentDb implements AutoCloseable {

    private final SQLiteDataSource dataSource;
    private final JdbcTemplate jdbc;

    private AgentDb(SQLiteDataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    public static AgentDb open(Path agentsRoot, String agentId) {
        try {
            Path agentDir = agentsRoot.resolve(agentId).resolve("agent");
            Files.createDirectories(agentDir);
            SQLiteDataSource ds = new SQLiteDataSource();
            ds.setUrl("jdbc:sqlite:" + agentDir.resolve(agentId + ".db"));
            org.sqlite.SQLiteConfig cfg = new org.sqlite.SQLiteConfig();
            cfg.setJournalMode(org.sqlite.SQLiteConfig.JournalMode.WAL);
            cfg.setBusyTimeout(5000);
            cfg.enforceForeignKeys(true);
            ds.setConfig(cfg);
            Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration/agent")
                .baselineOnMigrate(true)
                .load()
                .migrate();
            return new AgentDb(ds);
        } catch (Exception e) {
            throw new IllegalStateException("无法打开 agent 库: " + agentId, e);
        }
    }

    public DataSource ds() { return dataSource; }

    public JdbcTemplate jdbc() { return jdbc; }

    @Override
    public void close() {
        // SQLiteDataSource 无需显式关闭； WAL 文件由驱动管理
    }
}
