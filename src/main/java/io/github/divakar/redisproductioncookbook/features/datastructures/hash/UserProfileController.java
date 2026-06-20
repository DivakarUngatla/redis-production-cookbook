/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.hash;

import java.net.URI;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes REST endpoints for creating, reading, updating, and deleting Redis-backed
 * user profiles.
 */
@RestController
@RequestMapping("/api/user-profiles")
public class UserProfileController {

	private final UserProfileRepository repository;

	public UserProfileController(UserProfileRepository repository) {
		this.repository = repository;
	}

	@PostMapping
	public ResponseEntity<UserProfile> create(@Valid @RequestBody UserProfile profile) {
		UserProfile saved = repository.save(profile);
		return ResponseEntity.created(URI.create("/api/user-profiles/" + saved.getId())).body(saved);
	}

	@GetMapping("/{id}")
	public ResponseEntity<UserProfile> findById(@PathVariable String id) {
		return repository.findById(id)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	@PutMapping("/{id}")
	public ResponseEntity<UserProfile> update(
			@PathVariable String id, @Valid @RequestBody UserProfile profile) {
		if (!id.equals(profile.getId())) {
			return ResponseEntity.badRequest().build();
		}
		if (repository.findById(id).isEmpty()) {
			return ResponseEntity.notFound().build();
		}
		return ResponseEntity.ok(repository.save(profile));
	}

	@DeleteMapping("/{id}")
	public ResponseEntity<Void> delete(@PathVariable String id) {
		return repository.deleteById(id)
				? ResponseEntity.noContent().build()
				: ResponseEntity.notFound().build();
	}

}
