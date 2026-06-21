/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.set;

import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.stereotype.Repository;

/**
 * Stores user roles using Redis Sets.
 *
 * <p>Each user owns a Redis Set containing assigned roles.
 *
 * <pre>
 * user:roles:{user-123}
 *
 * ADMIN
 * AUTHOR
 * PREMIUM
 * </pre>
 *
 * Redis Sets automatically enforce uniqueness and provide efficient
 * membership checks.
 */
@Repository
public class UserRolesRepository {

    static final String KEY_PREFIX = "user:roles:";

    private final RedisTemplate<String, String> redisTemplate;
    private final SetOperations<String, String> sets;

    public UserRolesRepository(
            @Qualifier("userRolesRedisTemplate")
            RedisTemplate<String, String> redisTemplate) {

        this.redisTemplate = redisTemplate;
        this.sets = redisTemplate.opsForSet();
    }

    /**
     * Assigns a role to a user.
     *
     * @param userId unique user identifier
     * @param role role name
     * @return assigned role
     */
    public UserRole assignRole(String userId, String role) {

        validateUserId(userId);
        validateRole(role);

        sets.add(key(userId), role);

        return new UserRole(userId, role);
    }

    /**
     * Removes a role from a user.
     *
     * @param userId unique user identifier
     * @param role role name
     * @return true when a role was removed
     */
    public boolean removeRole(String userId, String role) {

        validateUserId(userId);
        validateRole(role);

        Long removed = sets.remove(key(userId), role);

        return removed != null && removed > 0;
    }

    /**
     * Checks whether a user has a role.
     *
     * @param userId unique user identifier
     * @param role role name
     * @return true if the role exists
     */
    public boolean hasRole(String userId, String role) {

        validateUserId(userId);
        validateRole(role);

        Boolean result = sets.isMember(key(userId), role);

        return Boolean.TRUE.equals(result);
    }

    /**
     * Returns all roles assigned to a user.
     *
     * @param userId unique user identifier
     * @return immutable set of roles
     */
    public Set<String> getRoles(String userId) {

        validateUserId(userId);

        Set<String> roles = sets.members(key(userId));

        return roles == null
                ? Set.of()
                : Set.copyOf(roles);
    }

    /**
     * Returns the number of roles assigned to a user.
     *
     * @param userId unique user identifier
     * @return role count
     */
    public long getRoleCount(String userId) {

        validateUserId(userId);

        Long size = sets.size(key(userId));

        return size == null ? 0 : size;
    }

    /**
     * Removes all roles assigned to a user.
     *
     * @param userId unique user identifier
     */
    public void clearRoles(String userId) {

        validateUserId(userId);

        redisTemplate.delete(key(userId));
    }

    /**
     * Returns common roles shared by two users.
     *
     * Demonstrates Redis SINTER.
     */
    public Set<String> intersectRoles(String userId1, String userId2) {

        validateUserId(userId1);
        validateUserId(userId2);

        Set<String> roles = sets.intersect(
                key(userId1),
                key(userId2));

        return roles == null
                ? Set.of()
                : Set.copyOf(roles);
    }

    /**
     * Returns all unique roles across two users.
     *
     * Demonstrates Redis SUNION.
     */
    public Set<String> unionRoles(String userId1, String userId2) {

        validateUserId(userId1);
        validateUserId(userId2);

        Set<String> roles = sets.union(
                key(userId1),
                key(userId2));

        return roles == null
                ? Set.of()
                : Set.copyOf(roles);
    }

    /**
     * Returns roles present in user1 but not user2.
     *
     * Demonstrates Redis SDIFF.
     */
    public Set<String> differenceRoles(String userId1, String userId2) {

        validateUserId(userId1);
        validateUserId(userId2);

        Set<String> roles = sets.difference(
                key(userId1),
                key(userId2));

        return roles == null
                ? Set.of()
                : Set.copyOf(roles);
    }

    private void validateUserId(String userId) {

        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User ID must not be blank");
        }
    }

    private void validateRole(String role) {

        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("Role must not be blank");
        }
    }

    private String key(String userId) {

        return KEY_PREFIX + "{" + userId + "}";
    }
}