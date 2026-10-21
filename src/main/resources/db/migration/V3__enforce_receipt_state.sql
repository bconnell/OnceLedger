-- A receipt may be reserved and completed within one transaction, but a
-- committed receipt must have an outcome consistent with its HTTP status.
ALTER TABLE idempotency_receipts
    ADD CONSTRAINT receipt_outcome_consistency CHECK ((
        (
            response_status = 0
            AND response_outcome IS NULL
            AND response_reason IS NULL
            AND amount_cents IS NULL
        )
        OR (
            response_status = 201
            AND response_outcome = 'posted'
            AND response_reason IS NULL
            AND amount_cents BETWEEN 1 AND 10000000
        )
        OR (
            response_status = 422
            AND response_outcome = 'rejected'
            AND response_reason IN ('unknown_account', 'insufficient_funds')
            AND amount_cents BETWEEN 1 AND 10000000
        )
    ) IS TRUE);
