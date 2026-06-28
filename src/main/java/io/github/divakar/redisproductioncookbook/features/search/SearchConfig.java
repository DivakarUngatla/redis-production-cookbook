/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.search;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/**
 * Wires the Jedis client used by the search module.
 *
 * <p>The rest of the cookbook talks to Redis through Spring Data Redis (Lettuce), which has
 * no RediSearch API. The search module therefore uses Jedis, whose {@code FT.*} commands
 * map directly onto the query engine. It connects to the same Redis host and port
 * configured by {@code spring.data.redis.*} and uses the default database 0, because the
 * query engine operates on db0 and its index names are global rather than per-database.</p>
 */
@Configuration
public class SearchConfig {

	private final String host;
	private final int port;

	public SearchConfig(
			@Value("${spring.data.redis.host:localhost}") String host,
			@Value("${spring.data.redis.port:6379}") int port) {
		this.host = host;
		this.port = port;
	}

	/**
	 * A Jedis client exposing RediSearch ({@code FT.*}) commands.
	 *
	 * @return a pooled Jedis client connected to the configured Redis
	 */
	@Bean(destroyMethod = "close")
	UnifiedJedis searchJedis() {
		return new JedisPooled(host, port);
	}
}
