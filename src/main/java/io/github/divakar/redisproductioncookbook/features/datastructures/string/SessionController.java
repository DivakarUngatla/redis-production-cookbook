/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.string;

import java.net.URI;
import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes HTTP operations for creating, reading, renewing, and deleting sessions.
 */
@Validated
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

	private static final String SESSION_ID_PATTERN = "[a-zA-Z0-9_-]{20,128}";

	private final UserSessionRepository repository;

	/**
	 * Creates the session controller.
	 *
	 * @param repository session persistence component
	 */
	public SessionController(UserSessionRepository repository) {
		this.repository = repository;
	}

	/**
	 * Creates an expiring user session.
	 *
	 * @param request validated session request
	 * @return HTTP 201 with the created session
	 */
	@PostMapping
	public ResponseEntity<UserSession> createSession(@Valid @RequestBody CreateSessionRequest request) {
		UserSession session = repository.createSession(
				request.userId(), Duration.ofSeconds(request.ttlSeconds()));
		URI location = URI.create("/api/sessions/" + session.sessionId());
		return ResponseEntity.created(location).body(session);
	}

	/**
	 * Retrieves an active session.
	 *
	 * @param sessionId session identifier
	 * @return the session or HTTP 404 when missing or expired
	 */
	@GetMapping("/{sessionId}")
	public ResponseEntity<UserSession> getSession(
			@PathVariable @Pattern(regexp = SESSION_ID_PATTERN) String sessionId) {
		return repository.getSession(sessionId)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Returns the session's remaining lifetime.
	 *
	 * @param sessionId session identifier
	 * @return remaining seconds or HTTP 404 when missing or expired
	 */
	@GetMapping("/{sessionId}/ttl")
	public ResponseEntity<SessionTtlResponse> getRemainingTtl(
			@PathVariable @Pattern(regexp = SESSION_ID_PATTERN) String sessionId) {
		return repository.getRemainingTtl(sessionId)
				.map(Duration::toSeconds)
				.map(seconds -> ResponseEntity.ok(new SessionTtlResponse(sessionId, seconds)))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Renews an active session using sliding expiration.
	 *
	 * @param sessionId session identifier
	 * @param request requested renewal lifetime
	 * @return renewed session or HTTP 404 when missing or expired
	 */
	@PutMapping("/{sessionId}/extend")
	public ResponseEntity<UserSession> extendSession(
			@PathVariable @Pattern(regexp = SESSION_ID_PATTERN) String sessionId,
			@Valid @RequestBody ExtendSessionRequest request) {
		return repository.extendSession(sessionId, Duration.ofSeconds(request.ttlSeconds()))
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Deletes a session immediately.
	 *
	 * @param sessionId session identifier
	 * @return HTTP 204 when deleted or HTTP 404 when absent
	 */
	@DeleteMapping("/{sessionId}")
	public ResponseEntity<Void> deleteSession(
			@PathVariable @Pattern(regexp = SESSION_ID_PATTERN) String sessionId) {
		return repository.deleteSession(sessionId)
				? ResponseEntity.noContent().build()
				: ResponseEntity.notFound().build();
	}

}
