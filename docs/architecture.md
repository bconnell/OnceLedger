# Architecture and correctness model

OnceLedger uses a single Spring Boot service backed by PostgreSQL. `TransferController` handles JSON and HTTP response metadata; `TransferService` owns validation, idempotency, balance changes, and persisted receipts. Spring JDBC executes SQL directly. Flyway owns schema changes and initial demonstration data.

## Successful request

1. Validate the idempotency key and canonical transfer fields. Reject invalid input before reserving the key.
2. Compute a SHA-256 fingerprint that includes a version marker and all transfer fields.
3. Attempt to insert a receipt reservation using `INSERT ... ON CONFLICT DO NOTHING` in the current database transaction.
4. On a new key, lock both account rows in account-ID order using `SELECT ... FOR UPDATE`.
5. Confirm that the accounts exist and the source has enough credits. For an accepted transfer, update both balances and insert two balanced ledger entries.
6. Save the completed HTTP status and response fields in the reservation row; commit the transaction.
7. Return the recorded result with `Idempotency-Replayed: false`.

All account updates, ledger entries, and the receipt are part of the **same PostgreSQL transaction**. A database failure aborts them together.

## Reused key

When another request uses the same key, PostgreSQL's unique constraint arbitrates concurrent inserts. An in-flight insert can make the contender wait until the first transaction commits or rolls back. After a commit, the contender reads the saved fingerprint and receipt. Matching details yield the original status, body, and transfer ID, with `Idempotency-Replayed: true`. Different details yield HTTP `409` without modifying the ledger.

After a rollback, the waiting request can reserve that key and execute normally. An incomplete status is not a valid committed receipt under the application's transactional design; encountering one is treated as an unexpected persistence invariant failure.

## Rejections and failures

Business rejections, such as insufficient credits or an unknown account, are final outcomes for their key and remain replayable even if a demo balance subsequently changes. Malformed requests return HTTP `400` without reserving a key.

The design covers **database-local side effects only**. It does not claim exactly-once execution across external APIs, email, brokers, or other systems. Distributed effects require a different design, such as a transactional outbox and an idempotent consumer.

## Concurrency and tradeoffs

The account locks are taken in stable order to coordinate competing valid keys. This prevents simultaneously approved transfers from spending the same source balance. PostgreSQL ensures atomic writes and durable receipts. The application intentionally favors clear transactional correctness over maximum throughput, horizontal partitioning, or eventual consistency.

A fresh instance of `TransferService` can replay the receipt using only the persisted database state. The integration tests also force a ledger insert failure to confirm that balances, entries, and the key reservation all roll back.

## Development exposure

The application binds to `127.0.0.1` by default and has no authentication. This is suitable for a developer's local demonstration, not an internet-facing service. The bundled credentials and seed balances are fixed disposable demo values, not production defaults.
