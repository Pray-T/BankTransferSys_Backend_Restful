package com.banktransfer.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class TransferRequest {

    @NotBlank
    @Size(max = 64)
    private String sourceAccountNumber;

    @NotBlank
    @Size(max = 64)
    private String targetAccountNumber;

    @NotNull
    @DecimalMin(value = "1")
    private BigDecimal amount;
}
