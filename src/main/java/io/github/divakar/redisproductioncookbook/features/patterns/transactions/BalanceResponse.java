/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.transactions;

/**
 * An account and its current balance.
 *
 * @param id the account id
 * @param balance the current balance
 */
public record BalanceResponse(String id, long balance) {
}
