# Redis Sorted Sets: Global Leaderboard

## Introduction

This module implements a production-style global leaderboard with Spring Boot and
Spring Data Redis. Scores are stored in the Redis Sorted Set `leaderboard:global`.
Each member is a stable `playerId`; display metadata is stored separately in a Redis
hash named `player:profile:{playerId}`.

Separating ranking data from player metadata lets an application rename a player
without changing leaderboard membership or score.

## Why Sorted Sets?

A Redis Sorted Set maintains unique members ordered by floating-point scores. Redis
updates the order as scores change, which makes top-N and rank queries efficient
without sorting the entire dataset in the application.

This example uses an absolute score: submitting a new score for an existing
`playerId` replaces its previous score.

## Why Not a Relational Database?

A relational database can implement a leaderboard with a query such as
`ORDER BY score DESC LIMIT N`. With appropriate indexes, this works well for many
workloads and may be the best choice when scores must participate in transactions
with other relational data.

Redis Sorted Sets maintain the ranking structure in memory as scores are written.
This provides efficient rank and top-N operations without repeatedly sorting large
datasets at query time. The trade-off is an additional data system with its own
memory, durability, consistency, and operational requirements.

Redis is not always better than SQL. The correct choice depends on leaderboard size,
update and query frequency, latency requirements, durability expectations, and
whether the relational database already satisfies the workload.

## Redis Key Design

```text
leaderboard:global                    # Sorted Set: playerId -> score
player:profile:{player-123}           # Hash: playerId, playerName
```

The REST API returns one-based ranks. Redis stores and returns ranks as zero-based,
so that conversion remains an internal repository detail.

## Architecture

```text
                         HTTP requests
                              |
                              v
                  +-------------------------+
                  |  LeaderboardController  |
                  +-------------------------+
                              |
                              v
                  +-------------------------+
                  |  LeaderboardRepository  |
                  +-------------------------+
                       |                 |
                       v                 v
       +---------------------------+   +----------------------------------+
       | Redis Sorted Set          |   | Redis Hash                       |
       | leaderboard:global        |   | player:profile:{playerId}        |
       |                           |   |                                  |
       | playerId -> score         |   | playerId, playerName             |
       | Ranking data              |   | Player metadata                  |
       +---------------------------+   +----------------------------------+
```

The repository combines ranking data from the Sorted Set with player metadata from
the Hash before returning leaderboard entries to the controller.

## Redis Commands

| Command | Purpose in this module |
|---------|------------------------|
| `ZADD` | Add a player or replace an existing score |
| `ZSCORE` | Read a player's score |
| `ZREVRANK` | Find rank with the highest score first |
| `ZREVRANGE` | Read the highest-scoring players |
| `ZREM` | Remove a player from the leaderboard |
| `HSET` | Store player profile metadata |
| `HGET` | Read the player name for an API response |
| `DEL` | Remove profile metadata when a player is removed |

### Example Commands

Add a player or update an existing player's absolute score:

```redis
ZADD leaderboard:global 9500 player-123
```

Read the player's score:

```redis
ZSCORE leaderboard:global player-123
```

Read the player's zero-based rank with the highest score ranked first:

```redis
ZREVRANK leaderboard:global player-123
```

Read the top ten players with their scores:

```redis
ZREVRANGE leaderboard:global 0 9 WITHSCORES
```

Remove a player from the leaderboard:

```redis
ZREM leaderboard:global player-123
```

## Time Complexity

| Operation | Complexity |
|-----------|------------|
| `ZADD` | `O(log N)` |
| `ZSCORE` | `O(1)` |
| `ZREM` | `O(log N)` |
| `ZRANK` / `ZREVRANK` | `O(log N)` |
| `ZRANGE` / `ZREVRANGE` | `O(log N + M)` |
| Hash field read/write | `O(1)` average |

`N` is the number of leaderboard members and `M` is the number of returned members.

## Common Use Cases

- Leaderboards
- Trending articles
- Top customers
- Gaming scores
- Search ranking
- Recommendation systems
- Priority queues

## Redis Internals: Hash Table and Skip List

Redis Sorted Sets are implemented using two complementary structures:

- A hash table maps each member to its score, enabling efficient membership and
  score lookups.
- A skip list maintains members in score order, enabling efficient rank, range,
  insertion, and removal operations.

The hash table explains the `O(1)` average `ZSCORE` lookup. Skip-list traversal and
updates give `ZADD`, `ZREM`, and rank operations their `O(log N)` complexity. A range
query costs `O(log N + M)`: Redis first locates the range and then returns `M` items.

For small Sorted Sets, Redis may use a compact listpack representation. It converts
to the larger representation when configured size or element thresholds are crossed.

## Cluster Considerations

`leaderboard:global` is one key and therefore belongs to one Redis Cluster hash slot
and one shard. This guarantees ordered results but also means a single very busy
global leaderboard can become a hot key. Partition large workloads by season,
region, game mode, or tenant when a truly global board is unnecessary.

Player profile keys use `{playerId}` hash tags so other data for the same player can
be deliberately co-located. They do not share a slot with `leaderboard:global`.
Consequently, updating the global score and player profile cannot be one Redis
Cluster transaction. Applications requiring strict cross-key atomicity must redesign
the key layout, use compensating repair, or accept eventual consistency. Putting all
keys under one global hash tag restores co-location but concentrates all traffic on
one shard.

For example:

```text
player:profile:{player-123}
```

Here, `player-123` is the hash tag. Redis Cluster maps keys containing the same hash
tag to the same hash slot. This enables future multi-key operations across related
player keys, provided every participating key uses `{player-123}`. It does not
co-locate those keys with `leaderboard:global`, which has no matching hash tag.

## Scaling Strategies

A single global leaderboard is one Redis key. Because one Redis key belongs to one
Redis Cluster hash slot, the entire leaderboard belongs to one shard. High write or
read volume can therefore make that shard and key a hot spot.

Common partitioning approaches include:

- Regional leaderboards, such as `leaderboard:region:apac`
- Seasonal leaderboards, such as `leaderboard:season:2026-summer`
- Per-game leaderboards, such as `leaderboard:game:chess`
- Per-tenant leaderboards, such as `leaderboard:tenant:acme`

Partitioning distributes traffic and bounds the cardinality of each Sorted Set. When
a global view is required, the application can query the relevant leaderboards and
merge their top results. That merge adds application complexity and may provide a
snapshot rather than a perfectly atomic global ranking, so the consistency contract
should be explicit.

## Run Example

Start Redis and the application:

```bash
docker compose up -d
./gradlew bootRun
```

The application expects Redis on `localhost:6379` unless connection properties are
overridden.

## curl Examples

Add or update scores:

```bash
curl -i -X POST http://localhost:8080/api/leaderboards/global/scores \
  -H 'Content-Type: application/json' \
  -d '{"playerId":"player-123","playerName":"Divakar","score":9500}'

curl -i -X POST http://localhost:8080/api/leaderboards/global/scores \
  -H 'Content-Type: application/json' \
  -d '{"playerId":"player-456","playerName":"Ada","score":12000}'
```

Read score and one-based rank:

```bash
curl http://localhost:8080/api/leaderboards/global/score/player-123
curl http://localhost:8080/api/leaderboards/global/rank/player-123
```

Read the top ten or request a smaller page:

```bash
curl 'http://localhost:8080/api/leaderboards/global/top'
curl 'http://localhost:8080/api/leaderboards/global/top?limit=2'
```

Remove a player:

```bash
curl -i -X DELETE http://localhost:8080/api/leaderboards/global/player/player-123
```

Inspect Redis directly:

```bash
docker exec redis-local redis-cli ZREVRANGE leaderboard:global 0 9 WITHSCORES
docker exec redis-local redis-cli HGETALL 'player:profile:{player-123}'
```

## Production Considerations

- Define whether incoming values replace a score or increment it. This example uses
  replacement semantics; cumulative scoring would use `ZINCRBY`.
- Cap the top-N limit to protect Redis, application memory, and response size. The
  controller limits requests to 100 entries.
- Decide how equal scores are ordered. Redis uses the member's lexicographical order
  as a tie-breaker; encode a different policy explicitly if the business requires it.
- Monitor hot-key latency, memory usage, cardinality, slow commands, and replication
  lag for large leaderboards.
- Fetching metadata per top player creates additional Redis operations. At high
  request volumes, pipeline reads, cache assembled responses, or denormalize immutable
  display data with a documented update strategy.
- Detect and repair partial updates between the Sorted Set and profile hashes,
  especially in Redis Cluster where the keys cannot participate in one transaction.
- Apply authentication, TLS, timeouts, bounded retries, persistence, replication,
  backup, and recovery settings appropriate to the leaderboard's durability needs.
- Avoid unbounded range queries and avoid `KEYS` in production maintenance code.

## Interview Notes

**Why is a Sorted Set suitable for a leaderboard?**

It combines unique membership with score ordering and supports score, rank, and
top-N queries without application-side sorting.

**Why use `ZREVRANK` instead of `ZRANK`?**

Leaderboards normally put the highest score first. `ZREVRANK` ranks in descending
score order.

**What happens when `ZADD` receives an existing member?**

Redis updates that member's score and repositions it in the ordering.

**How are Sorted Sets implemented?**

The general representation combines a hash table for member-to-score lookup and a
skip list for ordered access. Small sets can use a compact listpack.

**Can a global leaderboard scale across multiple Redis Cluster shards?**

Not as one native Sorted Set: a Redis key belongs to exactly one hash slot. Sharded
leaderboards require partitioned boards and an application-side merge, or a design
that accepts one shard for global ordering.

**What is the range-query complexity?**

`O(log N + M)`, where `N` is leaderboard cardinality and `M` is the result count.
