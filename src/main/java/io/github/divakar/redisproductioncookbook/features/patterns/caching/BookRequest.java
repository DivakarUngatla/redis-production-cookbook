/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.caching;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for writing a book; the id is taken from the path.
 *
 * @param title book title
 * @param author book author
 * @param price book price
 */
public record BookRequest(
		@NotBlank String title,
		@NotBlank String author,
		@NotNull @DecimalMin("0.0") BigDecimal price) {

	Book toBook(String id) {
		return new Book(id, title, author, price);
	}
}
