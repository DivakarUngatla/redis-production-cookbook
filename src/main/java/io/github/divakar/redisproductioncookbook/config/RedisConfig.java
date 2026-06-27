/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Provides application-level Redis configuration customizations.
 */
@Configuration
public class RedisConfig {

	/**
	 * Creates the string-serialized template used by data-structure examples.
	 *
	 * @param connectionFactory configured Redis connection factory
	 * @return a Redis template with readable string keys and values
	 */
	@Bean("leaderboardRedisTemplate")
	RedisTemplate<String, String> leaderboardRedisTemplate(RedisConnectionFactory connectionFactory) {
		StringRedisSerializer serializer = new StringRedisSerializer();
		RedisTemplate<String, String> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);
		template.setKeySerializer(serializer);
		template.setValueSerializer(serializer);
		template.setHashKeySerializer(serializer);
		template.setHashValueSerializer(serializer);
		template.afterPropertiesSet();
		return template;
	}

	@Bean("userRolesRedisTemplate")
	RedisTemplate<String, String> userRolesRedisTemplate(
			RedisConnectionFactory connectionFactory) {
		StringRedisSerializer serializer = new StringRedisSerializer();
		RedisTemplate<String, String> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);
		template.setKeySerializer(serializer);
		template.setValueSerializer(serializer);
		template.setHashKeySerializer(serializer);
		template.setHashValueSerializer(serializer);
		template.afterPropertiesSet();
		return template;
	}

	@Bean("bitmapRedisTemplate")
	RedisTemplate<String, String> bitmapRedisTemplate(RedisConnectionFactory connectionFactory) {
		StringRedisSerializer serializer = new StringRedisSerializer();
		RedisTemplate<String, String> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);
		template.setKeySerializer(serializer);
		template.setValueSerializer(serializer);
		template.afterPropertiesSet();
		return template;
	}

	@Bean("hyperLogLogRedisTemplate")
	RedisTemplate<String, String> hyperLogLogRedisTemplate(RedisConnectionFactory connectionFactory) {
		StringRedisSerializer serializer = new StringRedisSerializer();
		RedisTemplate<String, String> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);
		template.setKeySerializer(serializer);
		template.setValueSerializer(serializer);
		template.afterPropertiesSet();
		return template;
	}

	@Bean("geoRedisTemplate")
	RedisTemplate<String, String> geoRedisTemplate(RedisConnectionFactory connectionFactory) {
		StringRedisSerializer serializer = new StringRedisSerializer();
		RedisTemplate<String, String> template = new RedisTemplate<>();
		template.setConnectionFactory(connectionFactory);
		template.setKeySerializer(serializer);
		template.setValueSerializer(serializer);
		template.setHashKeySerializer(serializer);
		template.setHashValueSerializer(serializer);
		template.afterPropertiesSet();
		return template;
	}

}
