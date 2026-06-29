/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.transactions;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * A tiny wallet backed by Redis string balances, used to demonstrate transactions.
 *
 * <p>{@link #transfer} is the interesting part: it moves an amount between two accounts using
 * {@code WATCH}/{@code MULTI}/{@code EXEC} so the debit and credit commit atomically, and so a
 * concurrent transfer that changes a balance between our read and our {@code EXEC} causes the
 * {@code EXEC} to abort — at which point we retry with fresh balances (optimistic locking).</p>
 */
@Service
public class WalletService {

	static final String KEY_PREFIX = "txn:account:";
	static final int MAX_ATTEMPTS = 50;

	private final StringRedisTemplate redisTemplate;

	public WalletService(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	/**
	 * Creates or overwrites an account's balance.
	 */
	public void setBalance(String id, long balance) {
		redisTemplate.opsForValue().set(KEY_PREFIX + id, Long.toString(balance));
	}

	/**
	 * @return the account balance, or empty if the account does not exist
	 */
	public Optional<Long> getBalance(String id) {
		String value = redisTemplate.opsForValue().get(KEY_PREFIX + id);
		return value == null ? Optional.empty() : Optional.of(Long.parseLong(value));
	}

	/**
	 * Transfers {@code amount} from one account to another, retrying on optimistic conflicts.
	 *
	 * @param from source account id
	 * @param to destination account id
	 * @param amount positive amount to move
	 * @return the outcome with resulting balances and the number of attempts made
	 */
	public TransferResult transfer(String from, String to, long amount) {
		String fromKey = KEY_PREFIX + from;
		String toKey = KEY_PREFIX + to;

		for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
			Attempt result = runAttempt(fromKey, toKey, amount);
			switch (result.status()) {
				case OK -> {
					return TransferResult.of(TransferStatus.OK, result.fromBalance(), result.toBalance(), attempt);
				}
				case INSUFFICIENT -> {
					return TransferResult.of(
							TransferStatus.INSUFFICIENT_FUNDS, result.fromBalance(), result.toBalance(), attempt);
				}
				case NOT_FOUND -> {
					return TransferResult.of(
							TransferStatus.ACCOUNT_NOT_FOUND, result.fromBalance(), result.toBalance(), attempt);
				}
				case CONTENDED -> backoff(attempt); // a watched balance changed; retry
			}
		}

		long fromBalance = getBalance(from).orElse(0L);
		long toBalance = getBalance(to).orElse(0L);
		return TransferResult.of(TransferStatus.RETRIES_EXHAUSTED, fromBalance, toBalance, MAX_ATTEMPTS);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private Attempt runAttempt(String fromKey, String toKey, long amount) {
		return redisTemplate.execute(new SessionCallback<Attempt>() {
			@Override
			public Attempt execute(RedisOperations operations) {
				// WATCH both balances before reading, so EXEC aborts if either changes.
				operations.watch(List.of(fromKey, toKey));

				String fromValue = (String) operations.opsForValue().get(fromKey);
				String toValue = (String) operations.opsForValue().get(toKey);
				if (fromValue == null || toValue == null) {
					operations.unwatch();
					return new Attempt(AttemptStatus.NOT_FOUND, 0, 0);
				}

				long fromBalance = Long.parseLong(fromValue);
				long toBalance = Long.parseLong(toValue);
				if (fromBalance < amount) {
					operations.unwatch();
					return new Attempt(AttemptStatus.INSUFFICIENT, fromBalance, toBalance);
				}

				// Decision made; now queue the debit + credit and commit atomically.
				operations.multi();
				operations.opsForValue().set(fromKey, Long.toString(fromBalance - amount));
				operations.opsForValue().set(toKey, Long.toString(toBalance + amount));
				List<Object> execResult = operations.exec();

				if (execResult == null || execResult.isEmpty()) {
					// A watched balance changed between WATCH and EXEC; caller will retry.
					return new Attempt(AttemptStatus.CONTENDED, fromBalance, toBalance);
				}
				return new Attempt(AttemptStatus.OK, fromBalance - amount, toBalance + amount);
			}
		});
	}

	private static void backoff(int attempt) {
		try {
			// Small randomized backoff to reduce livelock under contention.
			Thread.sleep(ThreadLocalRandom.current().nextInt(1, 5 + Math.min(attempt, 10)));
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Transfer interrupted", interrupted);
		}
	}

	private enum AttemptStatus {
		OK, INSUFFICIENT, NOT_FOUND, CONTENDED
	}

	private record Attempt(AttemptStatus status, long fromBalance, long toBalance) {
	}
}
