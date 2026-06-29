/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.caching;

import java.math.BigDecimal;

/**
 * A book record, used as the cached entity in front of the slow {@link BookDatabase}.
 *
 * @param id unique identifier (cache key suffix)
 * @param title book title
 * @param author book author
 * @param price book price
 */
public record Book(String id, String title, String author, BigDecimal price) {
}
