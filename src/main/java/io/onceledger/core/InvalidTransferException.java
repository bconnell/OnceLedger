package io.onceledger.core;

public final class InvalidTransferException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvalidTransferException(String message) {
        super(message);
    }
}
