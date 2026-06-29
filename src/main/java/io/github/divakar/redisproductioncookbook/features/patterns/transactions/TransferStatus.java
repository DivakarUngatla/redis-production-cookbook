/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.transactions;

/**
 * Outcome of a wallet transfer attempt.
 */
public enum TransferStatus {

	/** The transfer committed. */
	OK,

	/** The source account did not have enough balance. */
	INSUFFICIENT_FUNDS,

	/** One of the accounts does not exist. */
	ACCOUNT_NOT_FOUND,

	/** Too many concurrent conflicts; the optimistic retries were exhausted. */
	RETRIES_EXHAUSTED
}
