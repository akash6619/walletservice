package com.walletservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Bootstraps the wallet service and enables Spring Boot component scanning and auto-configuration.
 */
@SpringBootApplication
public class WalletServiceApplication {

    /**
     * Starts the embedded web application.
     *
     * @param args command-line arguments forwarded to Spring Boot
     */
    public static void main(String[] args) {
        SpringApplication.run(WalletServiceApplication.class, args);
    }
}
