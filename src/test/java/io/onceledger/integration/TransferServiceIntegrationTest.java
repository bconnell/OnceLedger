package io.onceledger.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import io.onceledger.core.IdempotencyConflictException;
import io.onceledger.core.InvalidTransferException;
import io.onceledger.core.TransferReceipt;
import io.onceledger.core.TransferRequest;
import io.onceledger.core.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers
class TransferServiceIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("onceledger_test")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void registerDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransferService service;

    @Autowired
    private WebApplicationContext webContext;

    @BeforeEach
    void resetDemoLedger() {
        jdbc.update("DELETE FROM ledger_entries");
        jdbc.update("DELETE FROM idempotency_receipts");
        jdbc.update("UPDATE demo_accounts SET balance_cents = 100000 WHERE account_id = 'operating'");
        jdbc.update("UPDATE demo_accounts SET balance_cents = 25000 WHERE account_id = 'savings'");
    }

    @Test
    void firstSubmissionPostsOnceAndReplayReturnsSameResult() {
        TransferRequest request = new TransferRequest("operating", "savings", 2000L);
        TransferReceipt first = service.submit("invoice-test-001", request);
        TransferReceipt retry = service.submit("invoice-test-001", request);

        assertEquals(201, first.status());
        assertFalse(first.replayed());
        assertEquals(first.status(), retry.status());
        assertEquals(first.body(), retry.body());
        assertTrue(retry.replayed());
        assertEquals(98000L, balance("operating"));
        assertEquals(27000L, balance("savings"));
        assertEquals(2, count("ledger_entries"));
        assertEquals(1, count("idempotency_receipts"));
    }

    @Test
    void keyCannotBeReusedForDifferentAmountOrDirection() {
        service.submit("invoice-test-002", new TransferRequest("operating", "savings", 100L));
        assertThrows(IdempotencyConflictException.class, () ->
                service.submit("invoice-test-002", new TransferRequest("operating", "savings", 200L)));
        assertThrows(IdempotencyConflictException.class, () ->
                service.submit("invoice-test-002", new TransferRequest("savings", "operating", 100L)));
        assertEquals(2, count("ledger_entries"));
    }

    @Test
    void rejectedTransferIsDurablyReplayedWithoutEntries() {
        TransferRequest request = new TransferRequest("savings", "operating", 50000L);
        TransferReceipt first = service.submit("invoice-test-003", request);
        TransferReceipt replay = service.submit("invoice-test-003", request);
        assertEquals(422, first.status());
        assertEquals("insufficient_funds", first.body().reason());
        assertEquals(first.body(), replay.body());
        assertTrue(replay.replayed());
        assertEquals(0, count("ledger_entries"));
        assertEquals(25000L, balance("savings"));
    }

    @Test
    void unknownAccountIsRejectedAndRemembered() {
        TransferRequest request = new TransferRequest("absent", "savings", 10L);
        TransferReceipt first = service.submit("invoice-test-004", request);
        assertEquals(422, first.status());
        assertEquals("unknown_account", first.body().reason());
        assertTrue(service.submit("invoice-test-004", request).replayed());
        assertEquals(0, count("ledger_entries"));
    }

    @Test
    void invalidRequestsDoNotReserveKeys() {
        assertThrows(InvalidTransferException.class, () ->
                service.submit("short", new TransferRequest("operating", "savings", 10L)));
        assertThrows(InvalidTransferException.class, () ->
                service.submit("invalid-equal-accounts", new TransferRequest("savings", "savings", 10L)));
        assertThrows(InvalidTransferException.class, () ->
                service.submit("invalid-account-chars", new TransferRequest("bad\naccount", "savings", 10L)));
        assertEquals(0, count("idempotency_receipts"));
    }

    @Test
    void concurrentRetriesApplySingleBusinessEffect() throws Exception {
        TransferRequest request = new TransferRequest("operating", "savings", 1250L);
        CountDownLatch release = new CountDownLatch(1);
        List<Callable<TransferReceipt>> calls = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            calls.add(() -> {
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent test start timed out");
                }
                return service.submit("parallel-test-001", request);
            });
        }
        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Future<TransferReceipt>> results = new ArrayList<>();
            for (Callable<TransferReceipt> call : calls) {
                results.add(executor.submit(call));
            }
            release.countDown();
            UUID transferId = null;
            int initial = 0;
            for (Future<TransferReceipt> result : results) {
                TransferReceipt receipt = result.get(25, TimeUnit.SECONDS);
                if (!receipt.replayed()) {
                    initial++;
                }
                if (transferId == null) {
                    transferId = receipt.body().transferId();
                }
                assertEquals(transferId, receipt.body().transferId());
                assertEquals(201, receipt.status());
            }
            assertEquals(1, initial);
        }
        assertEquals(98750L, balance("operating"));
        assertEquals(26250L, balance("savings"));
        assertEquals(2, count("ledger_entries"));
        assertEquals(1, count("idempotency_receipts"));
    }

    @Test
    void competingDistinctKeysDoNotOverdrawSourceBalance() throws Exception {
        jdbc.update("UPDATE demo_accounts SET balance_cents = 500 WHERE account_id = 'operating'");
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<TransferReceipt> one = () -> {
                release.await();
                return service.submit("balance-race-one", new TransferRequest("operating", "savings", 400L));
            };
            Callable<TransferReceipt> two = () -> {
                release.await();
                return service.submit("balance-race-two", new TransferRequest("operating", "savings", 400L));
            };
            Future<TransferReceipt> first = executor.submit(one);
            Future<TransferReceipt> second = executor.submit(two);
            release.countDown();
            int firstStatus = first.get(25, TimeUnit.SECONDS).status();
            int secondStatus = second.get(25, TimeUnit.SECONDS).status();
            assertNotEquals(firstStatus, secondStatus);
        }
        assertEquals(100L, balance("operating"));
        assertEquals(25400L, balance("savings"));
        assertEquals(2, count("ledger_entries"));
    }

    @Test
    void transferReceiptPersistsAcrossServiceInstances() {
        TransferRequest request = new TransferRequest("operating", "savings", 180L);
        TransferReceipt original = service.submit("restart-proof-001", request);
        TransferService freshInstance = new TransferService(jdbc);
        TransferReceipt replayed = freshInstance.submit("restart-proof-001", request);
        assertEquals(original.body(), replayed.body());
        assertTrue(replayed.replayed());
        assertEquals(2, count("ledger_entries"));
    }

    @Test
    void rejectedTransferDoesNotBecomeValidAfterBalanceChanges() {
        TransferRequest request = new TransferRequest("savings", "operating", 40000L);
        TransferReceipt rejected = service.submit("frozen-rejection-001", request);
        jdbc.update("UPDATE demo_accounts SET balance_cents = 50000 WHERE account_id = 'savings'");
        TransferReceipt again = service.submit("frozen-rejection-001", request);
        assertEquals(422, rejected.status());
        assertEquals(rejected.body(), again.body());
        assertTrue(again.replayed());
        assertEquals(0, count("ledger_entries"));
    }

    @Test
    void databaseFailureRollsBackBalancesEntriesAndKeyReservation() {
        jdbc.execute("""
                CREATE FUNCTION reject_ledger_insert_for_test() RETURNS trigger AS $$
                BEGIN
                    RAISE EXCEPTION 'Simulated ledger insert failure';
                END;
                $$ LANGUAGE plpgsql
                """);
        jdbc.execute("""
                CREATE TRIGGER reject_ledger_insert_for_test
                BEFORE INSERT ON ledger_entries
                FOR EACH ROW EXECUTE FUNCTION reject_ledger_insert_for_test()
                """);
        TransferRequest request = new TransferRequest("operating", "savings", 400L);
        try {
            assertThrows(DataAccessException.class, () -> service.submit("rollback-proof-001", request));
        } finally {
            jdbc.execute("DROP TRIGGER reject_ledger_insert_for_test ON ledger_entries");
            jdbc.execute("DROP FUNCTION reject_ledger_insert_for_test()");
        }
        assertEquals(100000L, balance("operating"));
        assertEquals(25000L, balance("savings"));
        assertEquals(0, count("ledger_entries"));
        assertEquals(0, count("idempotency_receipts"));
        assertEquals(201, service.submit("rollback-proof-001", request).status());
    }

    @Test
    void opposingTransfersCompleteWithoutDeadlock() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<TransferReceipt> toSavings = () -> {
                release.await();
                return service.submit("opposite-first-001", new TransferRequest("operating", "savings", 500L));
            };
            Callable<TransferReceipt> toOperating = () -> {
                release.await();
                return service.submit("opposite-second-001", new TransferRequest("savings", "operating", 500L));
            };
            Future<TransferReceipt> first = executor.submit(toSavings);
            Future<TransferReceipt> second = executor.submit(toOperating);
            release.countDown();
            assertEquals(201, first.get(25, TimeUnit.SECONDS).status());
            assertEquals(201, second.get(25, TimeUnit.SECONDS).status());
        }
        assertEquals(100000L, balance("operating"));
        assertEquals(25000L, balance("savings"));
        assertEquals(4, count("ledger_entries"));
    }

    @Test
    void httpApiValidatesRequestsAndExposesReplayHeader() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        String transfer = """
                {"fromAccount":"operating","toAccount":"savings","amountCents":150}
                """;
        mvc.perform(get("/api/accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountId").value("operating"));
        mvc.perform(post("/api/transfers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(transfer))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/transfers")
                .header("Idempotency-Key", "http-key-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccount":"INVALID VALUE","toAccount":"savings","amountCents":150}
                        """))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/transfers")
                .header("Idempotency-Key", "http-key-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(transfer))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "false"))
                .andExpect(jsonPath("$.outcome").value("posted"));
        mvc.perform(post("/api/transfers")
                .header("Idempotency-Key", "http-key-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content(transfer))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true"));
        mvc.perform(post("/api/transfers")
                .header("Idempotency-Key", "http-key-001")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"fromAccount":"operating","toAccount":"savings","amountCents":151}
                        """))
                .andExpect(status().isConflict());
        assertEquals(2, count("ledger_entries"));
    }


    @Test
    void requestBoundaryValuesAreValidatedBeforeReservingKeys() {
        assertThrows(InvalidTransferException.class, () ->
                service.submit("seven77", new TransferRequest("operating", "savings", 1L)));
        assertThrows(InvalidTransferException.class, () ->
                service.submit("invalid-negative-amount", new TransferRequest("operating", "savings", -1L)));
        assertThrows(InvalidTransferException.class, () ->
                service.submit("invalid-zero-amount", new TransferRequest("operating", "savings", 0L)));
        assertThrows(InvalidTransferException.class, () ->
                service.submit("invalid-excess-amount", new TransferRequest("operating", "savings", 10_000_001L)));
        assertThrows(InvalidTransferException.class, () ->
                service.submit("invalid-null-amount", new TransferRequest("operating", "savings", null)));
        assertThrows(InvalidTransferException.class, () ->
                service.submit("invalid-space-account", new TransferRequest("not valid", "savings", 1L)));
        assertEquals(0, count("idempotency_receipts"));

        TransferReceipt minimum = service.submit("boundary-minimum", new TransferRequest("operating", "savings", 1L));
        assertEquals(201, minimum.status());
        TransferReceipt maximum = service.submit("boundary-maximum",
                new TransferRequest("operating", "savings", 10_000_000L));
        assertEquals(422, maximum.status());
        assertEquals("insufficient_funds", maximum.body().reason());
        assertEquals(2, count("idempotency_receipts"));
        assertEquals(2, count("ledger_entries"));
    }

    @Test
    void concurrentDifferentRequestsSharingAKeyRejectOneRequest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        String key = "race-different-body-001";
        try (var executor = Executors.newFixedThreadPool(2)) {
            Callable<Object> firstRequest = () -> {
                release.await();
                try {
                    return service.submit(key, new TransferRequest("operating", "savings", 111L));
                } catch (IdempotencyConflictException exception) {
                    return exception;
                }
            };
            Callable<Object> secondRequest = () -> {
                release.await();
                try {
                    return service.submit(key, new TransferRequest("operating", "savings", 222L));
                } catch (IdempotencyConflictException exception) {
                    return exception;
                }
            };
            Future<Object> first = executor.submit(firstRequest);
            Future<Object> second = executor.submit(secondRequest);
            release.countDown();
            Object firstResult = first.get(25, TimeUnit.SECONDS);
            Object secondResult = second.get(25, TimeUnit.SECONDS);
            assertNotEquals(firstResult.getClass(), secondResult.getClass());
            assertTrue(firstResult instanceof IdempotencyConflictException
                    || secondResult instanceof IdempotencyConflictException);
            TransferReceipt winner = firstResult instanceof TransferReceipt
                    ? (TransferReceipt) firstResult : (TransferReceipt) secondResult;
            assertEquals(201, winner.status());
            assertFalse(winner.replayed());
            assertEquals(winner.body(), service.submit(key, new TransferRequest(
                    "operating", "savings", winner.body().amountCents())).body());
            assertEquals(100000L - winner.body().amountCents(), balance("operating"));
            assertEquals(25000L + winner.body().amountCents(), balance("savings"));
        }
        assertEquals(1, count("idempotency_receipts"));
        assertEquals(2, count("ledger_entries"));
    }

    @Test
    void successfulTransfersPreserveLedgerAndBalanceConservation() {
        service.submit("conservation-first", new TransferRequest("operating", "savings", 750L));
        service.submit("conservation-second", new TransferRequest("savings", "operating", 200L));
        service.submit("conservation-first", new TransferRequest("operating", "savings", 750L));

        Long totalBalance = jdbc.queryForObject(
                "SELECT SUM(balance_cents) FROM demo_accounts", Long.class);
        Long totalDelta = jdbc.queryForObject(
                "SELECT SUM(delta_cents) FROM ledger_entries", Long.class);
        Integer invalidTransfers = jdbc.queryForObject("""
                SELECT COUNT(*) FROM (
                    SELECT transfer_id
                      FROM ledger_entries
                     GROUP BY transfer_id
                    HAVING COUNT(*) <> 2 OR SUM(delta_cents) <> 0
                ) AS invalid_entries
                """, Integer.class);
        assertEquals(125000L, totalBalance.longValue());
        assertEquals(0L, totalDelta.longValue());
        assertEquals(0, invalidTransfers.intValue());
        assertEquals(4, count("ledger_entries"));
        assertEquals(2, count("idempotency_receipts"));
    }

    @Test
    void httpValidationDoesNotCreateReceiptsForMalformedTransferBodies() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(webContext).build();
        String[] invalidBodies = {
            "{\"fromAccount\":\"operating\",\"toAccount\":\"savings\",\"amountCents\":0}",
            "{\"fromAccount\":\"operating\",\"toAccount\":\"savings\",\"amountCents\":10000001}",
            "{\"fromAccount\":\"operating\",\"toAccount\":\"savings\",\"amountCents\":null}",
            "{\"fromAccount\":\"operating\",\"toAccount\":\"operating\",\"amountCents\":1}",
            "{\"fromAccount\":\"operating\",\"toAccount\":\"savings\",\"amountCents\":1.5}",
            "{\"fromAccount\":\"operating\",\"toAccount\":\"savings\",\"amountCents\":1,\"unknown\":true}",
            "{not-json}"
        };
        for (String body : invalidBodies) {
            mvc.perform(post("/api/transfers")
                    .header("Idempotency-Key", "invalid-body-probe")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, count("idempotency_receipts"));
        assertEquals(0, count("ledger_entries"));
    }

    private long balance(String account) {
        return jdbc.queryForObject("SELECT balance_cents FROM demo_accounts WHERE account_id = ?",
                Long.class, account);
    }

    private int count(String table) {
        // The table name is a fixed test constant, never external input.
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
