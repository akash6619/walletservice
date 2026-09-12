package com.walletservice.model;

import java.time.Instant;
import java.util.UUID;

public record User(UUID userId, String name, boolean active, Instant createdAt) {
}
