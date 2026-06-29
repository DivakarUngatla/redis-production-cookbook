/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.transactions;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * Request to create or set an account's balance.
 *
 * @param balance the balance to set (non-negative)
 */
public record SetBalanceRequest(@NotNull @PositiveOrZero Long balance) {
}
