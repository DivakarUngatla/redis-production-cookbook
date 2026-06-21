/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.bitmap;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisStringCommands.BitOperation;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Demonstrates Redis Bitmap operations for
 * Daily Active Users (DAU) and Monthly Active Users (MAU).
 */
@Repository
public class UserActivityRepository {

    static final String DAILY_KEY_PREFIX = "active-users:";
    static final String MONTHLY_KEY_PREFIX = "monthly-active-users:";

    private final RedisTemplate<String, String> redisTemplate;

    public UserActivityRepository(
            @Qualifier("bitmapRedisTemplate")
            RedisTemplate<String, String> redisTemplate) {

        this.redisTemplate = redisTemplate;
    }

    /**
     * Marks a user as active for a given day.
     */
    public UserActivity markUserActive(
            long userId,
            LocalDate date) {

        validateUserId(userId);

        redisTemplate.opsForValue()
                .setBit(
                        dailyKey(date),
                        userId,
                        true);

        return new UserActivity(
                userId,
                date,
                true);
    }

    /**
     * Checks whether a user was active on a given day.
     */
    public boolean isUserActive(
            long userId,
            LocalDate date) {

        validateUserId(userId);

        Boolean active =
                redisTemplate.opsForValue()
                        .getBit(
                                dailyKey(date),
                                userId);

        return Boolean.TRUE.equals(active);
    }

    /**
     * Counts Daily Active Users.
     */
    public long getDailyActiveUsers(
            LocalDate date) {

        Long count =
                redisTemplate.execute((RedisCallback<Long>) connection ->
                        connection.bitCount(
                                dailyKey(date).getBytes()));

        return count == null ? 0 : count;
    }

    /**
     * Builds a monthly bitmap using BITOP OR
     * and returns Monthly Active Users.
     */
    public long calculateMonthlyActiveUsers(
            YearMonth month) {

        String destinationKey =
                monthlyKey(month);

        List<String> dailyKeys =
                month.atDay(1)
                        .datesUntil(
                                month.atEndOfMonth().plusDays(1))
                        .map(this::dailyKey)
                        .toList();

        if (dailyKeys.isEmpty()) {
            return 0;
        }

        redisTemplate.execute((RedisCallback<Void>) connection -> {

            byte[][] sourceKeys =
                    dailyKeys.stream()
                            .map(String::getBytes)
                            .toArray(byte[][]::new);

            connection.bitOp(
                    BitOperation.OR,
                    destinationKey.getBytes(),
                    sourceKeys);

            return null;
        });

        Long count =
                redisTemplate.execute((RedisCallback<Long>) connection ->
                        connection.bitCount(
                                destinationKey.getBytes()));

        return count == null ? 0 : count;
    }

    /**
     * Deletes a daily bitmap.
     */
    public void deleteDailyBitmap(
            LocalDate date) {

        redisTemplate.delete(
                dailyKey(date));
    }

    /**
     * Applies TTL to a daily bitmap.
     */
    public void expireDailyBitmap(
            LocalDate date,
            long seconds) {

        redisTemplate.expire(
                dailyKey(date),
                java.time.Duration.ofSeconds(seconds));
    }

    /**
     * Returns the number of Monthly Active Users from a previously
     * calculated monthly bitmap.
     *
     * @param month the year and month for which to retrieve active users
     * @return the count of unique active users for the given month, or 0 if not available
     */
    public long getMonthlyActiveUsers(YearMonth month) {
        Long count = redisTemplate.execute((RedisCallback<Long>) connection ->
                connection.bitCount(monthlyKey(month).getBytes()));
        return count != null ? count : 0;
    }

    public UserActivity markUserInactive(long userId, LocalDate date) {
        validateUserId(userId);

        redisTemplate.opsForValue().setBit(
                dailyKey(date),
                userId,
                false
        );

        return new UserActivity(userId, date, false);
    }

    private String dailyKey(
            LocalDate date) {

        return DAILY_KEY_PREFIX + date;
    }

    private String monthlyKey(
            YearMonth month) {

        return MONTHLY_KEY_PREFIX + month;
    }

    private void validateUserId(
            long userId) {

        if (userId < 0) {
            throw new IllegalArgumentException(
                    "User ID must be positive");
        }
    }
}