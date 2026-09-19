package com.walletservice.model;

import java.time.Instant;
import java.util.UUID;

/** Registered identity allowed to own a wallet and participate in transfers when active. */
public record User(UUID userId, String name, boolean active, Instant createdAt) {
}
