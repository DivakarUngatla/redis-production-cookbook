
package io.github.divakar.redisproductioncookbook.features.datastructures.bitmap;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/user-activity")
public class UserActivityController {

    private final UserActivityRepository repository;

    public UserActivityController(UserActivityRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/{date}/users/{userId}")
    public ResponseEntity<UserActivity> markUserActive(
            @PathVariable LocalDate date,
            @PathVariable long userId) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(repository.markUserActive(userId, date));
    }

    @DeleteMapping("/{date}/users/{userId}")
    public ResponseEntity<UserActivity> markUserInactive(
            @PathVariable LocalDate date,
            @PathVariable long userId) {

        return ResponseEntity.ok(
                repository.markUserInactive(userId, date));
    }

    @GetMapping("/{date}/users/{userId}")
    public ResponseEntity<Map<String, Object>> isUserActive(
            @PathVariable LocalDate date,
            @PathVariable long userId) {

        return ResponseEntity.ok(
                Map.of(
                        "userId", userId,
                        "date", date,
                        "active", repository.isUserActive(userId, date)));
    }

    @GetMapping("/{date}/count")
    public ResponseEntity<Map<String, Object>> getDailyActiveUsers(
            @PathVariable LocalDate date) {

        return ResponseEntity.ok(
                Map.of(
                        "date", date,
                        "dailyActiveUsers", repository.getDailyActiveUsers(date)));
    }

    @PostMapping("/monthly")
    public ResponseEntity<Map<String, Object>> calculateMonthlyActiveUsers(
            @RequestParam String month) {

        YearMonth yearMonth = YearMonth.parse(month);

        return ResponseEntity.ok(
                Map.of(
                        "month", month,
                        "monthlyActiveUsers",
                        repository.calculateMonthlyActiveUsers(yearMonth)));
    }

    @GetMapping("/monthly")
    public ResponseEntity<Map<String, Object>> getMonthlyActiveUsers(
            @RequestParam String month) {

        YearMonth yearMonth = YearMonth.parse(month);

        return ResponseEntity.ok(
                Map.of(
                        "month", month,
                        "monthlyActiveUsers",
                        repository.getMonthlyActiveUsers(yearMonth)));
    }
}
