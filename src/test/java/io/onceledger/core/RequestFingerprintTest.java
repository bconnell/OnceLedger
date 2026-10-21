package io.onceledger.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {
    @Test
    void identicalRequestsHaveIdenticalFingerprints() {
        TransferRequest request = new TransferRequest("operating", "savings", 150L);
        assertEquals(RequestFingerprint.compute(request), RequestFingerprint.compute(request));
    }

    @Test
    void changingTransferDirectionOrAmountChangesFingerprint() {
        String original = RequestFingerprint.compute(new TransferRequest("operating", "savings", 150L));
        assertNotEquals(original,
                RequestFingerprint.compute(new TransferRequest("savings", "operating", 150L)));
        assertNotEquals(original,
                RequestFingerprint.compute(new TransferRequest("operating", "savings", 151L)));
    }
}
