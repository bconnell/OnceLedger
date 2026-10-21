package io.onceledger.core;

import java.util.UUID;

public record TransferResponse(UUID transferId, String outcome, String reason, long amountCents) {
}
