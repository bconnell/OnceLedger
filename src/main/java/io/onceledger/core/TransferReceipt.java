package io.onceledger.core;

public record TransferReceipt(int status, TransferResponse body, boolean replayed) {
}
