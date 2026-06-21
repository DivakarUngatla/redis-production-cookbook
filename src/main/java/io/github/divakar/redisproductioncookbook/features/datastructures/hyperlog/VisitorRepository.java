/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.hyperlog;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Demonstrates Redis HyperLogLog operations for
 * unique visitor analytics.
 */
@Repository
public class VisitorRepository {

    static final String DAILY_KEY_PREFIX = "unique-visitors:";
    static final String MONTHLY_KEY_PREFIX = "monthly-unique-visitors:";

    private final HyperLogLogOperations<String, String> hyperLogLogOperations;
    private final RedisTemplate<String, String> redisTemplate;

    public VisitorRepository(
            @Qualifier("hyperLogLogRedisTemplate")
            RedisTemplate<String, String> redisTemplate) {

        this.redisTemplate = redisTemplate;
        this.hyperLogLogOperations = redisTemplate.opsForHyperLogLog();
    }

    /**
     * Records a visitor for a specific day.
     */
    public UniqueVisitor recordVisitor(
            String visitorId,
            LocalDate date) {

        validateVisitorId(visitorId);

        hyperLogLogOperations.add(
                dailyKey(date),
                visitorId);

        return new UniqueVisitor(
                visitorId,
                date);
    }

    /**
     * Returns estimated daily unique visitors.
     */
    public long getDailyUniqueVisitors(
            LocalDate date) {

        Long count =
                hyperLogLogOperations.size(
                        dailyKey(date));

        return count == null ? 0 : count;
    }

    /**
     * Builds a monthly HyperLogLog using PFMERGE
     * and returns estimated unique visitors.
     */
    public long buildMonthlyUniqueVisitors(
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

        hyperLogLogOperations.union(
                destinationKey,
                dailyKeys.toArray(new String[0]));

        Long count =
                hyperLogLogOperations.size(
                        destinationKey);

        return count == null ? 0 : count;
    }

    /**
     * Reads a previously built monthly HyperLogLog.
     */
    public long getMonthlyUniqueVisitors(
            YearMonth month) {

        Long count =
                hyperLogLogOperations.size(
                        monthlyKey(month));

        return count == null ? 0 : count;
    }

    /**
     * Deletes a daily HyperLogLog.
     */
    public void deleteDailyVisitors(
            LocalDate date) {

        redisTemplate.delete(
                dailyKey(date));
    }

    private String dailyKey(
            LocalDate date) {

        return DAILY_KEY_PREFIX + date;
    }

    private String monthlyKey(
            YearMonth month) {

        return MONTHLY_KEY_PREFIX + month;
    }

    private void validateVisitorId(
            String visitorId) {

        if (visitorId == null || visitorId.isBlank()) {
            throw new IllegalArgumentException(
                    "Visitor ID must not be blank");
        }
    }
}