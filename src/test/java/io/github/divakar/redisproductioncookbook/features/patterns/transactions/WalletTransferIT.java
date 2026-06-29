/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.transactions;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the wallet transfer, exercising {@code WATCH}/{@code MULTI}/{@code EXEC}.
 *
 * <p>Runs against a dedicated Redis database with unique account ids per test. A Redis instance
 * must be available on the configured host and port.</p>
 */
@SpringBootTest(properties = "spring.data.redis.database=15")
class WalletTransferIT {

	private final WalletService walletService;
	private final StringRedisTemplate redisTemplate;

	@Autowired
	WalletTransferIT(WalletService walletService, StringRedisTemplate redisTemplate) {
		this.walletService = walletService;
		this.redisTemplate = redisTemplate;
	}

	@AfterEach
	void clearAccounts() {
		Set<String> keys = redisTemplate.keys(WalletService.KEY_PREFIX + "*");
		if (!keys.isEmpty()) {
			redisTemplate.delete(keys);
		}
	}

	@Test
	void transferMovesFundsAtomically() {
		String alice = account();
		String bob = account();
		walletService.setBalance(alice, 100);
		walletService.setBalance(bob, 0);

		TransferResult result = walletService.transfer(alice, bob, 30);

		assertThat(result.status()).isEqualTo(TransferStatus.OK);
		assertThat(result.fromBalance()).isEqualTo(70);
		assertThat(result.toBalance()).isEqualTo(30);
		assertThat(walletService.getBalance(alice)).contains(70L);
		assertThat(walletService.getBalance(bob)).contains(30L);
	}

	@Test
	void transferRefusesOverdraftWithoutChangingBalances() {
		String alice = account();
		String bob = account();
		walletService.setBalance(alice, 50);
		walletService.setBalance(bob, 0);

		TransferResult result = walletService.transfer(alice, bob, 100);

		assertThat(result.status()).isEqualTo(TransferStatus.INSUFFICIENT_FUNDS);
		assertThat(walletService.getBalance(alice)).contains(50L);
		assertThat(walletService.getBalance(bob)).contains(0L);
	}

	@Test
	void transferToMissingAccountIsRejected() {
		String alice = account();
		walletService.setBalance(alice, 100);

		TransferResult result = walletService.transfer(alice, account(), 10);

		assertThat(result.status()).isEqualTo(TransferStatus.ACCOUNT_NOT_FOUND);
		assertThat(walletService.getBalance(alice)).contains(100L);
	}

	@Test
	void concurrentTransfersNeverViolateTheInvariant() throws InterruptedException, ExecutionException {
		String alice = account();
		String bob = account();
		walletService.setBalance(alice, 500);
		walletService.setBalance(bob, 500);
		long total = 1000;
		long amount = 5;
		int perDirection = 50;

		ExecutorService pool = Executors.newFixedThreadPool(16);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<TransferResult>> aToB = new ArrayList<>();
		List<Future<TransferResult>> bToA = new ArrayList<>();
		for (int i = 0; i < perDirection; i++) {
			aToB.add(pool.submit(() -> {
				start.await();
				return walletService.transfer(alice, bob, amount);
			}));
			bToA.add(pool.submit(() -> {
				start.await();
				return walletService.transfer(bob, alice, amount);
			}));
		}

		start.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

		long committedAToB = countOk(aToB);
		long committedBToA = countOk(bToA);

		long aliceBalance = walletService.getBalance(alice).orElseThrow();
		long bobBalance = walletService.getBalance(bob).orElseThrow();

		// Money is conserved and nobody is overdrawn, regardless of interleaving.
		assertThat(aliceBalance + bobBalance).isEqualTo(total);
		assertThat(aliceBalance).isNotNegative();
		assertThat(bobBalance).isNotNegative();
		// Final balances match exactly the net of committed transfers.
		long net = (committedBToA - committedAToB) * amount;
		assertThat(aliceBalance).isEqualTo(500 + net);
		assertThat(bobBalance).isEqualTo(500 - net);
	}

	private long countOk(List<Future<TransferResult>> futures) throws InterruptedException, ExecutionException {
		long ok = 0;
		for (Future<TransferResult> future : futures) {
			if (future.get().status() == TransferStatus.OK) {
				ok++;
			}
		}
		return ok;
	}

	private static String account() {
		return "acct-" + UUID.randomUUID();
	}
}
