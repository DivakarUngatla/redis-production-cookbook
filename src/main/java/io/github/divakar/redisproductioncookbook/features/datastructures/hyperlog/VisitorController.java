/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.hyperlog;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/visitors")
public class VisitorController {

    private final VisitorRepository repository;

    public VisitorController(
            VisitorRepository repository) {

        this.repository = repository;
    }

    /**
     * Records a visitor for a specific day.
     */
    @PostMapping("/{date}/{visitorId}")
    public ResponseEntity<UniqueVisitor> recordVisitor(
            @PathVariable LocalDate date,
            @PathVariable String visitorId) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(repository.recordVisitor(
                        visitorId,
                        date));
    }

    /**
     * Returns estimated daily unique visitors.
     */
    @GetMapping("/{date}/count")
    public ResponseEntity<Map<String, Object>> getDailyUniqueVisitors(
            @PathVariable LocalDate date) {

        return ResponseEntity.ok(
                Map.of(
                        "date", date,
                        "uniqueVisitors",
                        repository.getDailyUniqueVisitors(date)));
    }

    /**
     * Builds a monthly HyperLogLog using PFMERGE.
     */
    @PostMapping("/monthly")
    public ResponseEntity<Map<String, Object>> buildMonthlyUniqueVisitors(
            @RequestParam String month) {

        YearMonth yearMonth =
                YearMonth.parse(month);

        return ResponseEntity.ok(
                Map.of(
                        "month", month,
                        "uniqueVisitors",
                        repository.buildMonthlyUniqueVisitors(
                                yearMonth)));
    }

    /**
     * Reads a previously built monthly HyperLogLog.
     */
    @GetMapping("/monthly")
    public ResponseEntity<Map<String, Object>> getMonthlyUniqueVisitors(
            @RequestParam String month) {

        YearMonth yearMonth =
                YearMonth.parse(month);

        return ResponseEntity.ok(
                Map.of(
                        "month", month,
                        "uniqueVisitors",
                        repository.getMonthlyUniqueVisitors(
                                yearMonth)));
    }
}