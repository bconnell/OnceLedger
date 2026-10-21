# Verification

This project demonstrates database-local idempotency. Its correctness depends on PostgreSQL transactions, not on response caching or process memory. A green source scan does not establish that the application starts or that the database tests pass.

## Local verification

Run from the repository root with Java 21, Maven 3.9 or newer, and an available Docker daemon.

PowerShell 5.1 or later:

```powershell
.\scripts\verify.ps1
```

POSIX shell:

```sh
sh scripts/verify.sh
```

These scripts require each check to succeed before reporting success:

1. Scan tracked and untracked repository text for unfinished wording and common secret or personal-path markers.
2. Parse Java source using the JDK 21 compiler parser, without claiming to resolve dependencies.
3. Check that Docker responds and that the development Compose file is structurally valid.
4. Run `mvn -B -ntp -T 1 clean verify`. Maven applies Checkstyle, Java compilation with warnings treated as errors, unit tests, and the PostgreSQL integration suite.
5. Check the Git working tree for whitespace errors.

The scripts do not delete Docker volumes, change global Java or Maven settings, modify operating-system services, or start parallel builds. Testcontainers may download and start an isolated PostgreSQL image when Docker is available. The operator controls any Docker cleanup.

## Correctness checks

The integration suite includes:

| Behavior | Expected result |
| --- | --- |
| First valid transfer | One saved receipt, two balancing ledger entries, and updated account balances |
| Same key and same body | Original receipt returned without a second balance change |
| Same key and different body | Conflict and no second ledger write |
| Simultaneous duplicate submissions | One application effect |
| Simultaneous different bodies using one key | One successful request and one key conflict |
| Competing transfers spending the same credits | The source balance never becomes negative |
| Opposite-direction transfers | Ordered locks prevent the tested two-account deadlock |
| Failed ledger insertion | Account and receipt changes roll back |
| Missing account or insufficient credits | A saved, replayable rejection |
| Invalid request amount or account identifier | HTTP 400 and no receipt |
| Successful opposite-direction transfers | Two entries per transfer; total balances and ledger deltas conserved |
| Corrupted persisted receipt attempt | The database rejects contradictory status, outcome, and rejection reason values |

The test cases exercise these conditions, but their presence is not evidence of a passing test run.

## Evidence and limits

No successful full Maven verification run has been recorded for the current revision. Neither a browser preview of the HTML page nor the Java parser preflight qualifies as a running-application test. A screenshot should be added only after starting the actual Spring Boot service against PostgreSQL, submitting a transfer, and verifying its response and balances.

Before marking a release as verified, record the exact Git commit, JDK and Maven versions, database image version, commands, exit codes, failing or passing test counts, and a genuine application screenshot. Do not replace missing results with expected outcomes.

The application is a loopback-only demonstration. It has no authentication, user ownership, production secrets management, external payment integration, or multi-tenant isolation. Its database-local guarantees do not extend to external side effects.

## Publication review

The automatic text scan catches common placeholders and potential disclosures in text files. It cannot prove accuracy, originality, legal clearance, or that a feature operates correctly. Public documentation, UI wording, API examples, source comments, and release descriptions must be compared with the implementation and recorded results before publication.
