package com.knowbase.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 真实 MySQL 并发认领测试。默认跳过；设置 KNOWBASE_TEST_MYSQL_URL 指向名称含
 * test/it 的独立库后执行。测试只清理该独立库内的两张临时表。
 */
@EnabledIfEnvironmentVariable(named = "KNOWBASE_TEST_MYSQL_URL", matches = ".+")
class IngestionTaskMysqlIntegrationTest {

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        String url = System.getenv("KNOWBASE_TEST_MYSQL_URL");
        if (!url.toLowerCase().contains("test") && !url.toLowerCase().contains("_it")) {
            throw new IllegalStateException("refusing to run against a non-test database");
        }
        String username = System.getenv().getOrDefault("KNOWBASE_TEST_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("KNOWBASE_TEST_MYSQL_PASSWORD", "root");
        DriverManager.getConnection(url, username, password).close();
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, username, password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE IF EXISTS kb_ingestion_task");
        jdbc.execute("DROP TABLE IF EXISTS kb_document");
        jdbc.execute("""
                CREATE TABLE kb_document (
                    id BIGINT PRIMARY KEY,
                    status VARCHAR(16) NOT NULL,
                    error_code VARCHAR(64),
                    error_msg VARCHAR(2000),
                    updated_at DATETIME(3) NOT NULL
                ) ENGINE=InnoDB
                """);
        jdbc.execute("""
                CREATE TABLE kb_ingestion_task (
                    id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    doc_id BIGINT NOT NULL,
                    type VARCHAR(32) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    attempt INT NOT NULL,
                    max_attempt INT NOT NULL,
                    locked_by VARCHAR(64),
                    locked_at DATETIME(3),
                    error_code VARCHAR(64),
                    error_msg VARCHAR(2000),
                    started_at DATETIME(3),
                    finished_at DATETIME(3),
                    created_at DATETIME(3) NOT NULL,
                    updated_at DATETIME(3) NOT NULL,
                    KEY idx_claim (status, created_at),
                    CONSTRAINT fk_test_task_doc FOREIGN KEY (doc_id) REFERENCES kb_document(id)
                ) ENGINE=InnoDB
                """);
    }

    @AfterAll
    static void cleanUp() {
        if (jdbc != null) {
            jdbc.execute("DROP TABLE IF EXISTS kb_ingestion_task");
            jdbc.execute("DROP TABLE IF EXISTS kb_document");
        }
    }

    @BeforeEach
    void clearQueueTables() {
        jdbc.update("DELETE FROM kb_ingestion_task");
        jdbc.update("DELETE FROM kb_document");
    }

    @Test
    void twoInstancesClaimExactlyOnce() throws Exception {
        jdbc.update("INSERT INTO kb_document (id, status, updated_at) VALUES (1, 'PENDING', NOW(3))");
        jdbc.update("""
                INSERT INTO kb_ingestion_task
                (doc_id, type, status, attempt, max_attempt, created_at, updated_at)
                VALUES (1, 'INGEST', 'QUEUED', 0, 3, NOW(3), NOW(3))
                """);
        IngestionTaskRepository first = new IngestionTaskRepository(jdbc);
        IngestionTaskRepository second = new IngestionTaskRepository(jdbc);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = pool.submit(() -> {
                start.await();
                return first.claimNext(IngestionTaskRepository.TaskType.INGEST, "instance-a").isPresent();
            });
            Future<Boolean> b = pool.submit(() -> {
                start.await();
                return second.claimNext(IngestionTaskRepository.TaskType.INGEST, "instance-b").isPresent();
            });
            start.countDown();

            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }

        Integer running = jdbc.queryForObject(
                "SELECT COUNT(*) FROM kb_ingestion_task WHERE status = 'RUNNING' AND attempt = 1",
                Integer.class);
        String docStatus = jdbc.queryForObject(
                "SELECT status FROM kb_document WHERE id = 1", String.class);
        assertThat(running).isEqualTo(1);
        assertThat(docStatus).isEqualTo("PROCESSING");
    }

    @Test
    void staleRunningTaskIsRecoveredExactlyOnce() {
        jdbc.update("INSERT INTO kb_document (id, status, updated_at) VALUES (2, 'PROCESSING', NOW(3))");
        jdbc.update("""
                INSERT INTO kb_ingestion_task
                (doc_id, type, status, attempt, max_attempt, locked_by, locked_at, created_at, updated_at)
                VALUES (2, 'INGEST', 'RUNNING', 1, 3, 'dead-worker',
                        DATE_SUB(NOW(3), INTERVAL 11 MINUTE), NOW(3), NOW(3))
                """);
        IngestionTaskRepository repository = new IngestionTaskRepository(jdbc);

        int recovered = repository.recoverStale(
                java.time.LocalDateTime.now().minusMinutes(10),
                java.time.LocalDateTime.now());
        int secondScan = repository.recoverStale(
                java.time.LocalDateTime.now().minusMinutes(10),
                java.time.LocalDateTime.now());

        assertThat(recovered).isEqualTo(1);
        assertThat(secondScan).isZero();
        String taskStatus = jdbc.queryForObject(
                "SELECT status FROM kb_ingestion_task WHERE doc_id = 2", String.class);
        String lockedBy = jdbc.queryForObject(
                "SELECT locked_by FROM kb_ingestion_task WHERE doc_id = 2", String.class);
        String docStatus = jdbc.queryForObject(
                "SELECT status FROM kb_document WHERE id = 2", String.class);
        assertThat(taskStatus).isEqualTo("QUEUED");
        assertThat(lockedBy).isNull();
        assertThat(docStatus).isEqualTo("PENDING");
    }
}
