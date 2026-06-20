/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootApplication
public class RedisProductionCookbookApplication {

	public static void main(String[] args) {
		SpringApplication.run(RedisProductionCookbookApplication.class, args);
	}

	@Bean
	CommandLineRunner testRedis(StringRedisTemplate template) {
		return args -> {
			template.opsForValue().set("health", "ok");
			System.out.println(template.opsForValue().get("health"));
		};
	}

}
