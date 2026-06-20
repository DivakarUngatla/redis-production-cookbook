# Redis Lists: Recent Activity Feed

## Introduction

This module implements a production-style recent activity feed with Spring Boot and
Spring Data Redis. Each user has a Redis List stored under a key such as
`activity:feed:{user-123}`.

New events are inserted at the head of the List, so reads naturally return the newest
activity first. Every write also trims the List to the most recent 100 events,
providing predictable bounded history.

## Why Redis Lists?

Redis Lists preserve insertion order and support constant-time insertion and removal
at either end. They are useful when an application frequently needs the latest N
items and does not require arbitrary searching or field-level queries.

Using `LPUSH` places each new event at the front. `LRANGE` then reads the newest
events without application-side sorting, while `LTRIM` prevents unbounded growth.

## Why Not a Relational Database?

A relational database handles activity history well. An indexed query using a user
identifier and creation timestamp can provide durable history, filtering, analytics,
and pagination over large datasets.

Redis Lists are useful when the latest N records are requested frequently, ordering
matters, bounded history is acceptable, and low-latency retrieval is important. The
trade-off is limited query flexibility and a retention model that intentionally
discards older items.

Redis is not always better than SQL. Many production systems use Redis for the recent
view and archive the complete event history in a relational database, event stream,
or object store.

## Redis Key Design

```text
activity:feed:{userId}
```

Example:

```text
activity:feed:{user-123}
```

Each List element is a JSON-serialized `ActivityEvent` containing `eventId`,
`userId`, `eventType`, `description`, and `createdAt`.

## Architecture

```text
                  HTTP requests
                       |
                       v
          +--------------------------+
          | ActivityFeedController   |
          +--------------------------+
                       |
                       v
          +--------------------------+
          | ActivityFeedRepository   |
          +--------------------------+
                       |
                       v
          +--------------------------+
          | Redis List               |
          | activity:feed:{userId}   |
          |                          |
          | Newest                   |
          |   |                      |
          |   v                      |
          | event-5                  |
          | event-4                  |
          | event-3                  |
          | event-2                  |
          | event-1                  |
          +--------------------------+
```

The controller creates the event identifier and timestamp. The repository serializes
the event, inserts it at the List head, and trims the List atomically.

## Redis Commands

| Command | Purpose in this module |
|---------|------------------------|
| `LPUSH` | Insert a new activity at the head of the feed |
| `LRANGE` | Read recent activities in newest-first order |
| `LLEN` | Count retained activities |
| `LTRIM` | Keep only the newest configured number of events |
| `DEL` | Clear a user's complete activity history |

`LPUSH` and `LTRIM` execute together in a Lua script when an activity is added. This
keeps the 100-entry bound intact even when multiple application instances write to
the same feed concurrently.

## Example Commands

Add a new event to the front of a user's feed:

```redis
LPUSH activity:feed:{user-123} '{"eventId":"event-5","userId":"user-123","eventType":"LOGIN","description":"User logged in","createdAt":"2026-06-21T10:00:00Z"}'
```

Read the twenty newest activities:

```redis
LRANGE activity:feed:{user-123} 0 19
```

Count retained activities:

```redis
LLEN activity:feed:{user-123}
```

Keep only the latest 100 activities:

```redis
LTRIM activity:feed:{user-123} 0 99
```

Clear the feed:

```redis
DEL activity:feed:{user-123}
```

## Time Complexity

| Command | Complexity |
|---------|------------|
| `LPUSH` | `O(1)` |
| `RPUSH` | `O(1)` |
| `LPOP` | `O(1)` |
| `RPOP` | `O(1)` |
| `LLEN` | `O(1)` |
| `LRANGE` | `O(S + N)` |
| `LTRIM` | `O(N)` |

For `LRANGE`, `S` is the offset Redis must traverse and `N` is the number of returned
elements. For `LTRIM`, `N` represents the elements removed. Keeping reads near the
head and history bounded makes these costs predictable for this use case.

## Common Use Cases

- Activity feeds
- Audit logs
- Notification history
- Order history
- Recently viewed items
- Producer/consumer queues

## Redis List Internals

Redis Lists are implemented as quicklists. A quicklist is a linked list whose nodes
contain compact listpacks.

This hybrid representation provides:

- Memory efficiency by packing multiple small elements into contiguous storage
- Fast insertions and removals at the head and tail
- Ordered access without storing a separate score or timestamp index
- Configurable compression of interior quicklist nodes for additional memory savings

Traversing deep into a List still requires walking nodes and elements. Redis Lists
are therefore strongest for operations near either end and bounded range reads, not
random access across very large histories.

## Cluster Considerations

The user ID is a Redis Cluster hash tag in this key:

```text
activity:feed:{user-123}
```

Here, `user-123` is the hash tag. Any keys containing the same hash tag map to the
same Redis Cluster slot. For example:

```text
activity:feed:{user-123}
user:profile:{user-123}
```

These keys are co-located on the same shard, enabling future multi-key transactions
or Lua scripts for one user. Different user IDs distribute feeds across cluster slots
and shards.

The add-and-trim script touches only one List key, so it is safe in Redis Cluster.

## Scaling Strategies

Per-user Lists distribute naturally across a Redis Cluster and can support millions
of users when memory and throughput are sized carefully.

- **Bounded history:** retain only the latest 100 events so memory grows with active
  users rather than with every event ever produced.
- **Memory growth:** measure average serialized event size, quicklist overhead,
  allocator fragmentation, replication buffers, and persistence overhead.
- **Fan-out patterns:** writing an event into every follower's feed can amplify writes.
  Fan-out-on-read reduces writes but increases read and merge costs. Choose based on
  audience size and latency requirements.
- **Hot users:** celebrity or shared-account feeds may concentrate traffic on one key
  and one shard. Cache assembled responses, rate-limit writes, or partition the feed
  when one user becomes a sustained hot key.
- **Feed sharding:** the `{userId}` hash tag spreads different users across slots;
  monitor slot balance and add shards as data or traffic grows.
- **Archiving:** publish events to durable storage before trimming when historical
  audit, analytics, or compliance access is required.

## Run Example

Start Redis and the application:

```bash
docker compose up -d
./gradlew bootRun
```

The application expects Redis on `localhost:6379` unless connection properties are
overridden.

## curl Examples

Add activities:

```bash
curl -i -X POST http://localhost:8080/api/activity-feeds/events \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-123","eventType":"LOGIN","description":"User logged in"}'

curl -i -X POST http://localhost:8080/api/activity-feeds/events \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-123","eventType":"PURCHASE","description":"Order 789 was placed"}'
```

Read the twenty newest activities or request a smaller result:

```bash
curl 'http://localhost:8080/api/activity-feeds/user-123'
curl 'http://localhost:8080/api/activity-feeds/user-123?limit=5'
```

Read the retained activity count:

```bash
curl 'http://localhost:8080/api/activity-feeds/user-123/count'
```

Clear the user's feed:

```bash
curl -i -X DELETE 'http://localhost:8080/api/activity-feeds/user-123'
```

Inspect the List directly:

```bash
docker exec redis-local redis-cli LRANGE 'activity:feed:{user-123}' 0 9
docker exec redis-local redis-cli LLEN 'activity:feed:{user-123}'
```

## Production Considerations

- **Retention policy:** define whether the most recent 100 events satisfy product,
  support, audit, and compliance requirements. Make retention a deliberate contract.
- **Bounded history:** execute `LPUSH` and `LTRIM` atomically so concurrent writes
  cannot leave Lists above the configured bound.
- **Memory management:** monitor memory usage, fragmentation, evictions, hot keys,
  command latency, and average event size. Keep descriptions and payloads small.
- **Long-term archival:** write events to a durable database, Stream, or event broker
  before they are trimmed when complete history must be retained.
- **Avoid unbounded growth:** never append indefinitely without a trim, expiration,
  deletion, or archival policy.
- **Ordering semantics:** List order represents insertion order, not necessarily the
  event's `createdAt` order when producers submit delayed or out-of-order events.
- **Delivery guarantees:** a List is not an audit log. Persistence settings, failover,
  retries, and duplicate event handling determine whether writes can be lost or
  repeated.
- **Payload evolution:** version event schemas when consumers or archived data must
  survive incompatible model changes.
- **Sensitive data:** avoid placing secrets or unnecessary personal information in
  descriptions. Apply access control and encryption in transit.

## Interview Notes

**What is the difference between a Redis List and a Redis Stream?**

A List is an ordered sequence with simple push, pop, range, and blocking operations.
A Stream adds durable entry IDs, consumer groups, pending-entry tracking,
acknowledgements, and replay-oriented consumption.

**Why use `LPUSH` with `LTRIM`?**

`LPUSH` makes the newest event the first element. `LTRIM key 0 99` keeps only the 100
newest events, creating an efficient bounded recent-history pattern.

**What is the complexity of `LRANGE`?**

`LRANGE` is `O(S + N)`, where `S` is the start offset and `N` is the number of
returned elements.

**What is a quicklist?**

A quicklist is Redis's List representation: a linked list of compact listpack nodes.
It balances memory efficiency with fast operations at both ends.

**When would you choose a List instead of a Sorted Set?**

Choose a List when insertion order is the ranking criterion and operations focus on
the head or tail. Choose a Sorted Set when items require explicit scores, rank
queries, score updates, or ordered range queries by score.

**When would you migrate from a List to a Stream?**

Migrate when events need stable IDs, multiple consumer groups, acknowledgements,
pending-message recovery, replay, or stronger processing semantics than a simple
queue or recent-history List provides.
