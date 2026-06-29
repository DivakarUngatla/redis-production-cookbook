/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.transactions;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demonstrates Redis transactions via a wallet. The transfer endpoint uses
 * {@code WATCH}/{@code MULTI}/{@code EXEC} for an atomic, overdraft-safe debit + credit.
 *
 * <p>This is a <em>mechanism</em> module (like locks, caching, and rate-limit), so it roots at
 * {@code /api/transactions} with accounts nested beneath.</p>
 */
@Validated
@RestController
@RequestMapping("/api/transactions")
public class TransactionController {

	private final WalletService walletService;

	public TransactionController(WalletService walletService) {
		this.walletService = walletService;
	}

	/**
	 * Creates or sets an account's balance.
	 */
	@PutMapping("/accounts/{id}")
	public BalanceResponse setBalance(
			@PathVariable String id, @Valid @RequestBody SetBalanceRequest request) {
		walletService.setBalance(id, request.balance());
		return new BalanceResponse(id, request.balance());
	}

	/**
	 * Returns an account's current balance (404 if it does not exist).
	 */
	@GetMapping("/accounts/{id}")
	public BalanceResponse getBalance(@PathVariable String id) {
		long balance = walletService.getBalance(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Account not found"));
		return new BalanceResponse(id, balance);
	}

	/**
	 * Transfers an amount between two accounts atomically.
	 *
	 * @return 200 with the resulting balances on success; 409 if funds are insufficient or the
	 *     optimistic retries were exhausted; 404 if an account does not exist; 400 for a
	 *     same-account transfer
	 */
	@PostMapping("/transfer")
	public ResponseEntity<TransferResult> transfer(@Valid @RequestBody TransferRequest request) {
		if (request.from().equals(request.to())) {
			throw new ResponseStatusException(
					HttpStatus.BAD_REQUEST, "Source and destination accounts must differ");
		}
		TransferResult result = walletService.transfer(request.from(), request.to(), request.amount());
		return ResponseEntity.status(statusFor(result.status())).body(result);
	}

	private static HttpStatus statusFor(TransferStatus status) {
		return switch (status) {
			case OK -> HttpStatus.OK;
			case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case INSUFFICIENT_FUNDS, RETRIES_EXHAUSTED -> HttpStatus.CONFLICT;
		};
	}
}
