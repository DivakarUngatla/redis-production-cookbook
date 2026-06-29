/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.transactions;

/**
 * The result of a transfer, including the resulting balances and how many optimistic attempts
 * it took.
 *
 * @param status the outcome
 * @param fromBalance source balance after the transfer (or current balance if it did not commit)
 * @param toBalance destination balance after the transfer (or current balance if it did not commit)
 * @param attempts number of optimistic attempts made (a value above 1 means there was contention)
 */
public record TransferResult(TransferStatus status, long fromBalance, long toBalance, int attempts) {

	static TransferResult of(TransferStatus status, long fromBalance, long toBalance, int attempts) {
		return new TransferResult(status, fromBalance, toBalance, attempts);
	}
}
