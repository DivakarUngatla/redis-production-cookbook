/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.set;

import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.NotBlank;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Demonstrates Redis Set operations using user roles.
 */
@RestController
@RequestMapping("/api/user-roles")
public class UserRolesController {

    private final UserRolesRepository repository;

    public UserRolesController(UserRolesRepository repository) {
        this.repository = repository;
    }

    /**
     * Assigns roles to a user.
     *
     * Example:
     *
     * POST /api/user-roles/user-123
     *
     * [
     *   "ADMIN",
     *   "AUTHOR"
     * ]
     */
    @PostMapping("/{userId}")
    public ResponseEntity<Map<String, Object>> assignRoles(
            @PathVariable String userId,
            @RequestBody List<String> roles) {

        for (String role : roles) {
            repository.assignRole(userId, role);
        }

        Map<String, Object> response = Map.of(
                "userId", userId,
                "roles", repository.getRoles(userId),
                "roleCount", repository.getRoleCount(userId)
        );

        return ResponseEntity.ok(response);
    }

    /**
     * Returns all roles assigned to a user.
     */
    @GetMapping("/{userId}")
    public ResponseEntity<Set<String>> getRoles(
            @PathVariable String userId) {

        return ResponseEntity.ok(
                repository.getRoles(userId));
    }

    /**
     * Returns whether a user has a specific role.
     *
     * GET /api/user-roles/user-123/roles/ADMIN
     */
    @GetMapping("/{userId}/roles/{role}")
    public ResponseEntity<Map<String, Object>> hasRole(
            @PathVariable String userId,
            @PathVariable String role) {

        boolean assigned =
                repository.hasRole(userId, role);

        return ResponseEntity.ok(
                Map.of(
                        "userId", userId,
                        "role", role,
                        "assigned", assigned));
    }

    /**
     * Returns the number of roles assigned to a user.
     */
    @GetMapping("/{userId}/count")
    public ResponseEntity<Map<String, Object>> getRoleCount(
            @PathVariable String userId) {

        return ResponseEntity.ok(
                Map.of(
                        "userId", userId,
                        "roleCount",
                        repository.getRoleCount(userId)));
    }

    /**
     * Removes a specific role from a user.
     *
     * DELETE /api/user-roles/user-123/roles/ADMIN
     */
    @DeleteMapping("/{userId}/roles/{role}")
    public ResponseEntity<Void> removeRole(
            @PathVariable String userId,
            @PathVariable String role) {

        boolean removed =
                repository.removeRole(userId, role);

        return removed
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    /**
     * Removes all roles assigned to a user.
     *
     * DELETE /api/user-roles/user-123
     */
    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> clearRoles(
            @PathVariable String userId) {

        repository.clearRoles(userId);

        return ResponseEntity.noContent().build();
    }

    /**
     * Demonstrates Redis SINTER.
     */
    @GetMapping("/intersection")
    public ResponseEntity<Set<String>> intersectRoles(
            @RequestParam @NotBlank String userId1,
            @RequestParam @NotBlank String userId2) {

        return ResponseEntity.ok(
                repository.intersectRoles(
                        userId1,
                        userId2));
    }

    /**
     * Demonstrates Redis SUNION.
     */
    @GetMapping("/union")
    public ResponseEntity<Set<String>> unionRoles(
            @RequestParam @NotBlank String userId1,
            @RequestParam @NotBlank String userId2) {

        return ResponseEntity.ok(
                repository.unionRoles(
                        userId1,
                        userId2));
    }

    /**
     * Demonstrates Redis SDIFF.
     */
    @GetMapping("/difference")
    public ResponseEntity<Set<String>> differenceRoles(
            @RequestParam @NotBlank String userId1,
            @RequestParam @NotBlank String userId2) {

        return ResponseEntity.ok(
                repository.differenceRoles(
                        userId1,
                        userId2));
    }
}