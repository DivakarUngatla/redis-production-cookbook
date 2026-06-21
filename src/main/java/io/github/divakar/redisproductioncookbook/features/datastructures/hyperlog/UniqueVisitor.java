/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.hyperlog;

import java.time.LocalDate;

/**
 * Represents a website visitor on a given day.
 */
public record UniqueVisitor(
        String visitorId,
        LocalDate date
) {
}