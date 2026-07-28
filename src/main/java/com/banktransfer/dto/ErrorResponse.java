package com.banktransfer.dto;

import java.time.LocalDateTime;
import java.util.Map;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class ErrorResponse {
    private String code;
    private String message;
    private String path;
    private LocalDateTime timestamp;
    private Map<String, Object> details;
}
