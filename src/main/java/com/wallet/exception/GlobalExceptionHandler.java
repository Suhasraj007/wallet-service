package com.wallet.exception;

import com.wallet.dto.ErrorResponse;
import com.wallet.dto.InsufficientFundsResponse;
import com.wallet.dto.ValidationErrorResponse;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Status-code decisions, stated and defended:
 *
 * <p>400 - the request itself is malformed (bad JSON, failed validation,
 * non-UUID transfer id). 401 - no valid token (handled in AuthFilter).
 * 404 - the resource does not exist for this caller; a transfer you are not
 * a participant of returns the same 404 as a missing one, so ids leak
 * nothing. 409 - the idempotency key was already used with a different body.
 * 422 - the request is well-formed but unprocessable given wallet state
 * (insufficient funds, self-transfer); chosen over 402 (reserved, its
 * semantics are payment-scheme specific) and over 400 (nothing is wrong with
 * the request's form). 503 - the database is unreachable; the transfer path
 * fails closed rather than guessing (see readyz and the write-up).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ValidationErrorResponse validation(MethodArgumentNotValidException e) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .sorted()
                .toList();
        return new ValidationErrorResponse("validation_failed", details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse unreadable(HttpMessageNotReadableException e) {
        return new ErrorResponse("malformed_request_body", "request body must be valid JSON");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse typeMismatch(MethodArgumentTypeMismatchException e) {
        return new ErrorResponse("invalid_parameter", e.getName() + " has an invalid format");
    }

    @ExceptionHandler(SelfTransferException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse selfTransfer(SelfTransferException e) {
        return new ErrorResponse("self_transfer_not_allowed", e.getMessage());
    }

    @ExceptionHandler(InsufficientFundsException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public InsufficientFundsResponse insufficientFunds(InsufficientFundsException e) {
        return new InsufficientFundsResponse(
                "insufficient_funds", e.getTransferId(), e.getBalancePaise());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse idempotencyConflict(IdempotencyConflictException e) {
        return new ErrorResponse("idempotency_key_conflict", e.getMessage());
    }

    @ExceptionHandler({WalletNotFoundException.class, TransferNotFoundException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse notFound(RuntimeException e) {
        return new ErrorResponse("not_found", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse noRoute(NoResourceFoundException e) {
        return new ErrorResponse("not_found", "no such route");
    }

    // Without these two, the catch-all below would turn Spring's own protocol
    // errors into 500s - reporting a server fault for what is a client mistake.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ErrorResponse methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return new ErrorResponse("method_not_allowed", "this route does not accept that method");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    @ResponseStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
    public ErrorResponse unsupportedMediaType(HttpMediaTypeNotSupportedException e) {
        return new ErrorResponse("unsupported_media_type", "send application/json");
    }

    /**
     * The consistency-over-availability decision, implemented: if the
     * datastore is slow or unreachable, the write path REJECTS with 503
     * instead of guessing or queueing. The client retries with the SAME
     * idempotency_key, which is exactly what makes that retry safe.
     */
    @ExceptionHandler({CannotCreateTransactionException.class,
            DataAccessResourceFailureException.class,
            QueryTimeoutException.class})
    public ResponseEntity<ErrorResponse> databaseUnavailable(Exception e) {
        log.atWarn().setMessage("database_unavailable")
                .addKeyValue("cause", e.getClass().getSimpleName())
                .log();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "2")
                .body(new ErrorResponse("service_unavailable",
                        "datastore unreachable; retry with the same idempotency_key"));
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse unhandled(Exception e) {
        // The correlation id is already in the MDC, so this line is enough to
        // find the request in the logs.
        log.error("unhandled_error", e);
        return new ErrorResponse("internal_error", "something went wrong");
    }
}
