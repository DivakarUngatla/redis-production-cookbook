/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.transactions;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Request to transfer an amount from one account to another.
 *
 * @param from the source account id
 * @param to the destination account id
 * @param amount the amount to move (positive)
 */
public record TransferRequest(
		@NotBlank String from,
		@NotBlank String to,
		@NotNull @Positive Long amount) {
}
