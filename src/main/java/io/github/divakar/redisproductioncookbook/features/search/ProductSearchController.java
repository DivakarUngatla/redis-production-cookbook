/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.search;

import java.math.BigDecimal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes HTTP operations for indexing products and searching them by relevance.
 */
@Validated
@RestController
@RequestMapping("/api/products")
public class ProductSearchController {

	private final ProductSearchRepository repository;

	public ProductSearchController(ProductSearchRepository repository) {
		this.repository = repository;
	}

	/**
	 * Saves (creates or replaces) a product, making it searchable.
	 *
	 * @param request validated product request
	 * @return the saved product
	 */
	@PostMapping
	public Product save(@Valid @RequestBody IndexProductRequest request) {
		return repository.save(new Product(
				request.id(),
				request.name(),
				request.description(),
				request.brand(),
				request.category(),
				request.price()));
	}

	/**
	 * Searches products by free text with optional tag and price filters, ranked by BM25.
	 *
	 * @param q free-text query, or blank to match all
	 * @param brand optional brand tag filter
	 * @param category optional category tag filter
	 * @param minPrice optional inclusive minimum price
	 * @param maxPrice optional inclusive maximum price
	 * @param limit maximum number of results, from 1 through 100
	 * @return the ranked search results
	 */
	@GetMapping("/search")
	public ResponseEntity<SearchResponse> search(
			@RequestParam(defaultValue = "") String q,
			@RequestParam(required = false) String brand,
			@RequestParam(required = false) String category,
			@RequestParam(required = false) BigDecimal minPrice,
			@RequestParam(required = false) BigDecimal maxPrice,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
		return ResponseEntity.ok(
				repository.search(q, brand, category, minPrice, maxPrice, limit));
	}

	/**
	 * Removes a product from the index.
	 *
	 * @param id unique product identifier
	 * @return 204 when deleted, 404 when not found
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id) {
		return repository.delete(id)
				? ResponseEntity.noContent().build()
				: ResponseEntity.notFound().build();
	}
}
