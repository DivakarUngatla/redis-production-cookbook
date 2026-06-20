/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.hash;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a user profile stored as fields in a Redis hash.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserProfile {

	@NotBlank
	@Pattern(regexp = "[a-zA-Z0-9_-]+", message = "must contain only letters, numbers, '_' or '-'")
	private String id;

	@NotBlank
	private String name;

	@NotBlank
	@Email
	private String email;

	@Min(0)
	@Max(150)
	private int age;

}
