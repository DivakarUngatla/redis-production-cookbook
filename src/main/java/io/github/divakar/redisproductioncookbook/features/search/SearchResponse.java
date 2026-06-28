/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.search;

import java.util.List;

/**
 * API response for a product search.
 *
 * @param query the effective query string that was executed
 * @param total total number of documents that matched (may exceed the page size)
 * @param results the page of results, most relevant first
 */
public record SearchResponse(String query, long total, List<ProductSearchResult> results) {
}
