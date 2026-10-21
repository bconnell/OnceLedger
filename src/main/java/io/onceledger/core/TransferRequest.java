package io.onceledger.core;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record TransferRequest(
        @NotNull @Pattern(regexp = "[a-z][a-z0-9-]{1,31}") String fromAccount,
        @NotNull @Pattern(regexp = "[a-z][a-z0-9-]{1,31}") String toAccount,
        @NotNull @Min(1) @Max(10000000) Long amountCents) {
}
