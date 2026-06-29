/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.querying.search;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the Redis Search product repository.
 *
 * <p>The query engine operates on db0 and uses global index names, so these tests cannot be
 * isolated by database. Instead each test tags its products with a unique brand, always
 * filters by it, and deletes them afterwards. A Redis instance with the query engine
 * (Redis 8 or Redis Stack) must be available on the configured host and port.</p>
 */
@SpringBootTest
class ProductSearchRepositoryIT {

	private final ProductSearchRepository repository;
	private final List<String> createdIds = new ArrayList<>();

	@Autowired
	ProductSearchRepositoryIT(ProductSearchRepository repository) {
		this.repository = repository;
	}

	@AfterEach
	void cleanUp() {
		createdIds.forEach(repository::delete);
		createdIds.clear();
	}

	@Test
	void ranksMoreRelevantMatchHigher() {
		String brand = uniqueBrand();
		// OR semantics: both documents match "iphone", but only a- also matches "pro".
		// Matching more of the query terms gives a- the higher BM25 score. Names are the same
		// length and descriptions identical, so field-length normalization can't cancel the
		// extra matching term.
		index(new Product("a-" + brand, "iPhone Pro", "smartphone", brand, "phone",
				new BigDecimal("999.00")));
		index(new Product("b-" + brand, "iPhone Air", "smartphone", brand, "phone",
				new BigDecimal("799.00")));

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertThat(repository.search("iphone pro", brand, null, null, null, 10).total())
						.isEqualTo(2));

		SearchResponse response = repository.search("iphone pro", brand, null, null, null, 10);

		assertThat(response.results()).hasSize(2);
		// Results come back ordered by relevance: the fuller match (a- matches both terms)
		// ranks ahead of the partial match (b- matches only "iphone").
		assertThat(response.results().stream().map(ProductSearchResult::id).toList())
				.containsExactly("a-" + brand, "b-" + brand);
	}

	@Test
	void matchesAnyTermWithOrSemantics() {
		String brand = uniqueBrand();
		// Each product matches only one of the query terms; OR semantics returns both.
		index(new Product("a-" + brand, "iPhone Pro", "phone", brand, "phone",
				new BigDecimal("999.00")));
		index(new Product("b-" + brand, "Galaxy Charger", "accessory", brand, "accessory",
				new BigDecimal("29.00")));

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertThat(repository.search("iphone galaxy", brand, null, null, null, 10).total())
						.isEqualTo(2));
	}

	@Test
	void appliesTagAndPriceFilters() {
		String brand = uniqueBrand();
		index(new Product("a-" + brand, "iPhone 16 Pro", "flagship", brand, "phone",
				new BigDecimal("999.00")));
		index(new Product("b-" + brand, "iPhone Charger", "accessory", brand, "accessory",
				new BigDecimal("29.00")));

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertThat(repository.search("", brand, null, null, null, 10).total()).isEqualTo(2));

		SearchResponse response =
				repository.search("", brand, "phone", new BigDecimal("500"), new BigDecimal("2000"), 10);

		assertThat(response.results()).hasSize(1);
		assertThat(response.results().get(0).id()).isEqualTo("a-" + brand);
		assertThat(response.results().get(0).price()).isEqualByComparingTo("999.00");
	}

	@Test
	void deleteRemovesProductFromIndex() {
		String brand = uniqueBrand();
		index(new Product("a-" + brand, "iPhone 16 Pro", "flagship", brand, "phone",
				new BigDecimal("999.00")));

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertThat(repository.search("", brand, null, null, null, 10).total()).isEqualTo(1));

		assertThat(repository.delete("a-" + brand)).isTrue();

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertThat(repository.search("", brand, null, null, null, 10).total()).isZero());
	}

	private void index(Product product) {
		repository.save(product);
		createdIds.add(product.id());
	}

	private static String uniqueBrand() {
		return "brand" + UUID.randomUUID().toString().replace("-", "");
	}
}
