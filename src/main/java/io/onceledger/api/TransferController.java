package io.onceledger.api;

import java.util.List;

import io.onceledger.core.AccountBalance;
import io.onceledger.core.TransferReceipt;
import io.onceledger.core.TransferRequest;
import io.onceledger.core.TransferResponse;
import io.onceledger.core.TransferService;
import jakarta.validation.Valid;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TransferController {
    private final TransferService service;

    public TransferController(TransferService service) {
        this.service = service;
    }

    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> transfer(
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody TransferRequest request) {
        TransferReceipt receipt = service.submit(key, request);
        return ResponseEntity.status(HttpStatusCode.valueOf(receipt.status()))
                .header("Idempotency-Replayed", Boolean.toString(receipt.replayed()))
                .body(receipt.body());
    }

    @GetMapping("/accounts")
    public List<AccountBalance> accounts() {
        return service.accounts();
    }
}
