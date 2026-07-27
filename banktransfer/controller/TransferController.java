package com.banktransfer.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.banktransfer.dto.TransferRequest;
import com.banktransfer.dto.TransferResponse;
import com.banktransfer.service.TransferService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/transfers")
@Validated
@Tag(name = "Transfers", description = "계좌 간 이체 API")
public class TransferController {

    private final TransferService transferService; 

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @Operation(summary = "이체 생성", description = "Idempotency-Key 헤더를 사용하여 중복 이체를 방지합니다.")
    @PostMapping  
    public ResponseEntity<TransferResponse> createTransfer(
            @Parameter(description = "중복 방지를 위한 키", required = true)
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody TransferRequest request) { 
        TransferResponse response = transferService.createTransfer(request, idempotencyKey); 
        return ResponseEntity.created(URI.create("/api/transfers/" + response.getTransferId())).body(response); 
    }

    @Operation(summary = "이체 조회")
    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable Long id) { 
        return ResponseEntity.ok(transferService.getTransfer(id)); 
    }
}

