/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.streams;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Recovers order events left unacknowledged by a crashed or stalled worker.
 *
 * <p>On a fixed interval it inspects the consumer group's Pending Entries List with
 * {@code XPENDING}, and for any entry idle longer than {@link #MIN_IDLE} it claims
 * ownership with {@code XCLAIM} and reprocesses it. This is what upholds at-least-once
 * delivery across a consumer failure: an entry is never lost, only redelivered, so
 * processing must be idempotent.</p>
 */
@Component
public class PendingEventRecovery {

	/** Consumer name that owns reclaimed entries. */
	static final String RECOVERY_CONSUMER = "recovery";

	/** Minimum idle time before a pending entry is considered stuck and reclaimed. */
	static final Duration MIN_IDLE = Duration.ofSeconds(30);

	private static final int SCAN_BATCH = 100;

	private static final Logger log = LoggerFactory.getLogger(PendingEventRecovery.class);

	private final StringRedisTemplate redisTemplate;
	private final OrderEventProcessor processor;

	/**
	 * Creates the pending-event recovery component.
	 *
	 * @param redisTemplate string-serialized Redis template
	 * @param processor shared processor used to reprocess reclaimed entries
	 */
	public PendingEventRecovery(StringRedisTemplate redisTemplate, OrderEventProcessor processor) {
		this.redisTemplate = redisTemplate;
		this.processor = processor;
	}

	/**
	 * Periodically reclaims and reprocesses stuck pending entries.
	 */
	@Scheduled(fixedDelay = 10_000L)
	public void reclaimStuckEntries() {
		StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();

		PendingMessages pending;
		try {
			pending = streamOps.pending(
					StreamsConfig.STREAM_KEY, StreamsConfig.GROUP, Range.unbounded(), SCAN_BATCH);
		}
		catch (Exception exception) {
			// The stream/group may not exist yet on a brand-new instance; nothing to recover.
			return;
		}

		for (PendingMessage message : pending) {
			if (message.getElapsedTimeSinceLastDelivery().compareTo(MIN_IDLE) < 0) {
				continue;
			}
			reclaim(streamOps, message);
		}
	}

	private void reclaim(StreamOperations<String, Object, Object> streamOps, PendingMessage message) {
		List<MapRecord<String, Object, Object>> claimed = streamOps.claim(
				StreamsConfig.STREAM_KEY,
				StreamsConfig.GROUP,
				RECOVERY_CONSUMER,
				MIN_IDLE,
				message.getId());

		for (MapRecord<String, Object, Object> record : claimed) {
			log.info("Reclaiming stuck entry {} (idle {}, delivered {} times)",
					record.getId().getValue(),
					message.getElapsedTimeSinceLastDelivery(),
					message.getTotalDeliveryCount());
			processor.process(
					RECOVERY_CONSUMER,
					record.getId(),
					stringField(record, "type"),
					stringField(record, "payload"));
		}
	}

	private static String stringField(MapRecord<String, Object, Object> record, String field) {
		Object value = record.getValue().get(field);
		return value == null ? null : value.toString();
	}
}
