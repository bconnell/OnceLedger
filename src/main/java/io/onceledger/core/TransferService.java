package io.onceledger.core;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {
    private final JdbcTemplate jdbc;

    public TransferService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A unique key is reserved, the account mutation is applied, and the receipt is
     * completed in one database transaction. An identical concurrent request blocks
     * on the key's unique constraint until this transaction commits or rolls back.
     */
    @Transactional
    public TransferReceipt submit(String key, TransferRequest request) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{7,127}")) {
            throw new InvalidTransferException("Idempotency-Key must be 8 to 128 permitted characters.");
        }
        if (request == null || !validAccountId(request.fromAccount())
                || !validAccountId(request.toAccount()) || request.amountCents() == null
                || request.amountCents() < 1 || request.amountCents() > 10_000_000) {
            throw new InvalidTransferException("The transfer request is invalid.");
        }
        if (request.fromAccount().equals(request.toAccount())) {
            throw new InvalidTransferException("Source and destination accounts must differ.");
        }

        String fingerprint = RequestFingerprint.compute(request);
        UUID transferId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO idempotency_receipts
                    (idempotency_key, request_fingerprint, transfer_id, response_status)
                VALUES (?, ?, ?, 0)
                ON CONFLICT (idempotency_key) DO NOTHING
                """, key, fingerprint, transferId);

        if (inserted == 0) {
            return replay(key, fingerprint);
        }

        TransferReceipt result = applyTransfer(transferId, request);
        jdbc.update("""
                UPDATE idempotency_receipts
                   SET response_status = ?, response_outcome = ?, response_reason = ?, amount_cents = ?
                 WHERE idempotency_key = ?
                """, result.status(), result.body().outcome(), result.body().reason(),
                result.body().amountCents(), key);
        return result;
    }

    private TransferReceipt replay(String key, String fingerprint) {
        return jdbc.queryForObject("""
                SELECT request_fingerprint, transfer_id, response_status,
                       response_outcome, response_reason, amount_cents
                  FROM idempotency_receipts
                 WHERE idempotency_key = ?
                """, (rs, rowNum) -> {
            if (!fingerprint.equals(rs.getString("request_fingerprint"))) {
                throw new IdempotencyConflictException();
            }
            int status = rs.getInt("response_status");
            if (status == 0) {
                throw new IllegalStateException("A committed idempotency receipt is incomplete.");
            }
            var body = new TransferResponse(
                    rs.getObject("transfer_id", UUID.class),
                    rs.getString("response_outcome"),
                    rs.getString("response_reason"),
                    rs.getLong("amount_cents"));
            return new TransferReceipt(status, body, true);
        }, key);
    }

    private TransferReceipt applyTransfer(UUID transferId, TransferRequest request) {
        // Lock in account-id order, independent of transfer direction, to avoid deadlocks.
        List<AccountBalance> locked = jdbc.query("""
                SELECT account_id, balance_cents
                  FROM demo_accounts
                 WHERE account_id IN (?, ?)
                 ORDER BY account_id
                 FOR UPDATE
                """, (rs, rowNum) -> new AccountBalance(
                rs.getString("account_id"), rs.getLong("balance_cents")),
                request.fromAccount(), request.toAccount());
        Map<String, Long> balances = locked.stream().collect(
                Collectors.toMap(AccountBalance::accountId, AccountBalance::balanceCents));

        if (locked.size() != 2) {
            return rejected(transferId, request.amountCents(), "unknown_account");
        }
        if (balances.get(request.fromAccount()) < request.amountCents()) {
            return rejected(transferId, request.amountCents(), "insufficient_funds");
        }

        jdbc.update("UPDATE demo_accounts SET balance_cents = balance_cents - ? WHERE account_id = ?",
                request.amountCents(), request.fromAccount());
        jdbc.update("UPDATE demo_accounts SET balance_cents = balance_cents + ? WHERE account_id = ?",
                request.amountCents(), request.toAccount());
        jdbc.update("""
                INSERT INTO ledger_entries (transfer_id, account_id, delta_cents)
                VALUES (?, ?, ?), (?, ?, ?)
                """, transferId, request.fromAccount(), -request.amountCents(),
                transferId, request.toAccount(), request.amountCents());
        return new TransferReceipt(201,
                new TransferResponse(transferId, "posted", null, request.amountCents()), false);
    }

    private static TransferReceipt rejected(UUID transferId, long amountCents, String reason) {
        return new TransferReceipt(422,
                new TransferResponse(transferId, "rejected", reason, amountCents), false);
    }

    private static boolean validAccountId(String accountId) {
        return accountId != null && accountId.matches("[a-z][a-z0-9-]{1,31}");
    }

    @Transactional(readOnly = true)
    public List<AccountBalance> accounts() {
        return jdbc.query("""
                SELECT account_id, balance_cents FROM demo_accounts ORDER BY account_id
                """, (rs, rowNum) -> new AccountBalance(
                rs.getString("account_id"), rs.getLong("balance_cents")));
    }
}
