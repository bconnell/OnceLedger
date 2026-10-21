package io.onceledger.core;

public final class IdempotencyConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public IdempotencyConflictException() {
        super("This idempotency key belongs to a different transfer request.");
    }
}
