package com.wallet.web;

import com.wallet.auth.Caller;
import com.wallet.dto.TransferDetailsResponse;
import com.wallet.dto.TransferRequest;
import com.wallet.dto.TransferResponse;
import com.wallet.model.TransferRecord;
import com.wallet.service.TransferResult;
import com.wallet.service.TransferService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    /**
     * 201 for a fresh apply AND for a replayed apply - a retry with the same
     * key returns the byte-identical original outcome, status code included.
     */
    @PostMapping("/transfers")
    public ResponseEntity<TransferResponse> create(@Caller String caller,
                                                   @Valid @RequestBody TransferRequest request) {
        TransferResult result = transferService.transfer(
                caller, request.toUser(), request.amountPaise(), request.idempotencyKey());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new TransferResponse(result.transferId(), "APPLIED",
                        result.newBalancePaise()));
    }

    @GetMapping("/transfers/{id}")
    public TransferDetailsResponse get(@Caller String caller, @PathVariable UUID id) {
        TransferRecord transfer = transferService.details(caller, id);
        return new TransferDetailsResponse(
                transfer.id(),
                transfer.fromUser(),
                transfer.toUser(),
                transfer.amountPaise(),
                transfer.status().name(),
                transfer.createdAt());
    }
}
