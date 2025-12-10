package com.banktransfer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;

@SpringBootApplication
@ConfigurationPropertiesScan
@OpenAPIDefinition(
        info = @Info(
                title = "Bank Transfer API",
                version = "1.0",
                description = "RESTful API for bank account transfers Backend without Front"
        )
)
public class BankTransferApplication {

    public static void main(String[] args) {
        SpringApplication.run(BankTransferApplication.class, args);
    }
}

