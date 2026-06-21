/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.set;

import jakarta.validation.constraints.NotBlank;

/**
 * Represents a role assignment for a user.
 */
public record UserRole(

        @NotBlank
        String userId,

        @NotBlank
        String role) {
}