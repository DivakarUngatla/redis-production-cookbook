/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.querying.search;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.search.Document;
import redis.clients.jedis.search.FTCreateParams;
import redis.clients.jedis.search.FTSearchParams;
import redis.clients.jedis.search.IndexDataType;
import redis.clients.jedis.search.SearchResult;
import redis.clients.jedis.search.schemafields.NumericField;
import redis.clients.jedis.search.schemafields.SchemaField;
import redis.clients.jedis.search.schemafields.TagField;
import redis.clients.jedis.search.schemafields.TextField;

/**
 * Indexes products as Redis hashes and searches them with the Redis query engine.
 *
 * <p>On startup it ensures the {@code products-idx} index exists over {@code product:}
 * hashes. Indexing a product is a plain {@code HSET}; because the key matches the index
 * prefix, the inverted index updates automatically. Searches use {@code FT.SEARCH} with the
 * BM25 scorer and return documents ordered by relevance.</p>
 */
@Repository
public class ProductSearchRepository {

	static final String INDEX = "products-idx";
	static final String KEY_PREFIX = "product:";

	private static final Logger log = LoggerFactory.getLogger(ProductSearchRepository.class);

	private final UnifiedJedis jedis;

	public ProductSearchRepository(UnifiedJedis jedis) {
		this.jedis = jedis;
	}

	/**
	 * Creates the search index if it does not already exist.
	 */
	@PostConstruct
	void ensureIndex() {
		try {
			jedis.ftInfo(INDEX);
			log.info("Search index {} already present", INDEX);
		}
		catch (JedisDataException missing) {
			List<SchemaField> schema = List.of(
					TextField.of("name").weight(5.0),
					TextField.of("description").weight(1.0),
					TagField.of("brand"),
					TagField.of("category"),
					NumericField.of("price").sortable());
			try {
				jedis.ftCreate(
						INDEX,
						FTCreateParams.createParams().on(IndexDataType.HASH).prefix(KEY_PREFIX),
						schema);
				log.info("Created search index {}", INDEX);
			}
			catch (JedisDataException alreadyExists) {
				// Another instance created it first (index names are global) — safe to ignore.
				log.info("Search index {} created concurrently", INDEX);
			}
		}
	}

	/**
	 * Saves (creates or replaces) a product.
	 *
	 * <p>This writes the product hash with {@code HSET}; because the key matches the index
	 * prefix, the query engine updates the search index automatically — there is no separate
	 * "add to index" step.</p>
	 *
	 * @param product the product to save
	 * @return the saved product
	 */
	public Product save(Product product) {
		Map<String, String> fields = new HashMap<>();
		fields.put("name", product.name());
		putIfPresent(fields, "description", product.description());
		putIfPresent(fields, "brand", product.brand());
		putIfPresent(fields, "category", product.category());
		fields.put("price", product.price().toPlainString());

		jedis.hset(KEY_PREFIX + product.id(), fields);
		return product;
	}

	/**
	 * Removes a product from the index by deleting its hash.
	 *
	 * @param id unique product identifier
	 * @return {@code true} when a product was deleted
	 */
	public boolean delete(String id) {
		return jedis.del(KEY_PREFIX + id) > 0;
	}

	/**
	 * Searches products by free text with optional tag and price filters, ranked by BM25.
	 *
	 * @param text free-text query, or {@code null}/blank to match all
	 * @param brand optional brand tag filter
	 * @param category optional category tag filter
	 * @param minPrice optional inclusive minimum price
	 * @param maxPrice optional inclusive maximum price
	 * @param limit maximum number of results to return
	 * @return the ranked search results
	 */
	public SearchResponse search(
			String text,
			String brand,
			String category,
			BigDecimal minPrice,
			BigDecimal maxPrice,
			int limit) {

		String query = buildQuery(text, brand, category, minPrice, maxPrice);

		FTSearchParams params = FTSearchParams.searchParams()
				.scorer("BM25")
				.withScores()
				.limit(0, limit);

		SearchResult result = jedis.ftSearch(INDEX, query, params);

		List<ProductSearchResult> hits = new ArrayList<>();
		for (Document document : result.getDocuments()) {
			hits.add(toResult(document));
		}
		return new SearchResponse(query, result.getTotalResults(), hits);
	}

	private String buildQuery(
			String text, String brand, String category, BigDecimal minPrice, BigDecimal maxPrice) {
		List<String> clauses = new ArrayList<>();
		String textClause = textClause(text);
		if (textClause != null) {
			clauses.add(textClause);
		}
		if (brand != null && !brand.isBlank()) {
			clauses.add("@brand:{" + escapeTag(brand) + "}");
		}
		if (category != null && !category.isBlank()) {
			clauses.add("@category:{" + escapeTag(category) + "}");
		}
		if (minPrice != null || maxPrice != null) {
			String low = minPrice == null ? "-inf" : minPrice.toPlainString();
			String high = maxPrice == null ? "+inf" : maxPrice.toPlainString();
			clauses.add("@price:[" + low + " " + high + "]");
		}
		return clauses.isEmpty() ? "*" : String.join(" ", clauses);
	}

	/**
	 * Builds the free-text portion of the query using OR semantics, so a multi-word query
	 * matches documents containing <em>any</em> of the terms (BM25 ranks fuller matches
	 * higher). The OR group is parenthesised so any tag/price filters still apply as AND.
	 *
	 * @return the text clause, or {@code null} when there is no text to search
	 */
	private static String textClause(String text) {
		if (text == null || text.isBlank()) {
			return null;
		}
		String[] tokens = text.trim().split("\\s+");
		return tokens.length == 1 ? tokens[0] : "(" + String.join("|", tokens) + ")";
	}

	private ProductSearchResult toResult(Document document) {
		String id = document.getId();
		String productId = id.startsWith(KEY_PREFIX) ? id.substring(KEY_PREFIX.length()) : id;
		return new ProductSearchResult(
				productId,
				stringField(document, "name"),
				stringField(document, "description"),
				stringField(document, "brand"),
				stringField(document, "category"),
				priceField(document),
				document.getScore());
	}

	private static void putIfPresent(Map<String, String> fields, String key, String value) {
		if (value != null && !value.isBlank()) {
			fields.put(key, value);
		}
	}

	private static String stringField(Document document, String field) {
		Object value = document.get(field);
		return value == null ? null : value.toString();
	}

	private static BigDecimal priceField(Document document) {
		String value = stringField(document, "price");
		return value == null ? null : new BigDecimal(value);
	}

	private static String escapeTag(String tag) {
		// Escape characters that are special inside a RediSearch tag filter.
		return tag.trim().replaceAll("([,.<>{}\\[\\]\"':;!@#$%^&*()\\-+=~ ])", "\\\\$1");
	}
}
