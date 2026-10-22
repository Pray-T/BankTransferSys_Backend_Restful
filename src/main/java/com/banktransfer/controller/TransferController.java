package com.banktransfer.controller;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
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

    @Operation(
            summary = "이체 생성",
            description = "Idempotency-Key는 필수입니다. 같은 키와 같은 요청은 같은 이체를 반환하고, 다른 요청은 409입니다. 서버는 키를 생성하지 않습니다.")
    @PostMapping
    public ResponseEntity<TransferResponse> createTransfer(
            @Parameter(description = "클라이언트가 발급한 멱등성 키 (필수, 생략·공백이면 400)", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody TransferRequest request) {
        TransferResponse response = transferService.createTransfer(request, idempotencyKey);
        String echoedKey = StringUtils.hasText(idempotencyKey) ? idempotencyKey.trim() : idempotencyKey;
        return ResponseEntity.created(URI.create("/api/transfers/" + response.getTransferId()))
                .header("Idempotency-Key", echoedKey)
                .body(response);
    }

    @Operation(summary = "이체 조회")
    @GetMapping("/{id}")
    public ResponseEntity<TransferResponse> getTransfer(@PathVariable Long id) {
        return ResponseEntity.ok(transferService.getTransfer(id));
    }
}
