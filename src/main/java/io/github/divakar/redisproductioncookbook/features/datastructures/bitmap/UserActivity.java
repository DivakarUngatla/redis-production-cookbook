/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.bitmap;

import java.time.LocalDate;

/**
 * Represents a user's activity on a specific day.
 */
public record UserActivity(
        long userId,
        LocalDate date,
        boolean active
) {
}