package io.onceledger.api;

import io.onceledger.core.IdempotencyConflictException;
import io.onceledger.core.InvalidTransferException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> conflict(IdempotencyConflictException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, exception.getMessage());
        detail.setTitle("Idempotency key conflict");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(detail);
    }

    @ExceptionHandler(InvalidTransferException.class)
    public ResponseEntity<ProblemDetail> invalid(InvalidTransferException exception) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, exception.getMessage());
        detail.setTitle("Invalid transfer request");
        return ResponseEntity.badRequest().body(detail);
    }
}
