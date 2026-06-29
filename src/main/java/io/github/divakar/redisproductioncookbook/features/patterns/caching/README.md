# Redis Caching: Strategies, Invalidation, and Stampede Protection

## Introduction

Caching is the reason most teams reach for Redis. The idea is simple — keep a fast copy of
slow data in memory so most reads never touch the slow system of record (a database, an API,
an expensive computation). The *hard* parts are not the lookup; they are **which caching
strategy** to use, **how to invalidate** stale entries, and how to survive **cache
stampedes** and **memory pressure** in production.

This module demonstrates the four classic strategies — **cache-aside, write-through,
write-behind, refresh-ahead** — against a deliberately **slow `Book` "database"** (an
in-memory store with artificial latency and read/write counters, so you can *see* when the
cache saves a trip). It also implements **cache-stampede protection** by reusing the
[distributed lock](../locks/README.md), and documents eviction and TTL practices.

> **Don't skip:** the [strategy comparison](#choosing-a-strategy), [cache invalidation](#cache-invalidation-the-hard-part),
> and [cache stampede](#cache-stampede-thundering-herd) sections — that's where the
> production and interview value is. The lookup itself is the easy 10%.

## Why Cache with Redis?

- **Latency.** A Redis `GET` is sub-millisecond and in-memory; a database query is
  milliseconds to seconds. Caching turns a hot read from a DB hit into a memory hit.
- **Load shedding.** The cache absorbs the read traffic the database would otherwise serve,
  protecting it during spikes.
- **Cost.** Fewer database reads means smaller, cheaper databases for read-heavy workloads.
- **Shared across instances.** Unlike an in-process map, a Redis cache is shared by every
  application instance, so they all benefit from one warm entry and see consistent data.

The trade-off is a second copy of the data that can go **stale** — which is what the
strategies below are really about.

## The Cache-Aside Pattern (the default)

Cache-aside (a.k.a. lazy loading) is the most common strategy and the one to reach for first.
The **application** owns the cache; Redis knows nothing about the database.

```text
READ  get(id):
        value = GET cache:book:{id}
        if hit  -> return value                      (fast path, no DB)
        if miss -> value = db.find(id)               (slow path)
                   SET cache:book:{id} value EX ttl   (populate for next time)
                   return value

WRITE update(book):
        db.save(book)                                 (source of truth first)
        DEL cache:book:{id}                           (invalidate, don't update)
```

Two deliberate choices:

- **Lazy population** — only data that is actually read gets cached, so the cache fills with
  the hot set and cold data never wastes memory.
- **Invalidate on write, don't update** — deleting the key is safer than writing the new
  value into the cache, because a concurrent reader could otherwise re-populate a stale value
  between your DB write and cache write. The next read re-loads fresh. (This is the
  read-through-on-miss behaviour.)

## The Four Strategies

### 1. Cache-Aside (Lazy Loading)

Described above. The app reads from cache, falls back to the DB on a miss, and invalidates on
write.

- **Pros:** simple; only caches what's used; resilient (a cache outage just means slower
  reads).
- **Cons:** every miss pays the DB latency; first read after a write is always a miss;
  risk of stampede on a hot key (see below).

### 2. Write-Through

Writes go to the cache **and** the database **synchronously**, so the cache is always
populated and fresh after a write.

```text
WRITE update(book):
        db.save(book)                                 (synchronous)
        SET cache:book:{id} book EX ttl               (synchronous, same call)
READ:   served from cache (already warm after writes)
```

- **Pros:** reads after writes are always hits; cache never serves stale data it wrote.
- **Cons:** every write pays both latencies; caches data that may never be read (cold writes
  waste memory); doesn't help the very first read of never-written data.

### 3. Write-Behind (Write-Back)

Writes go to the cache **immediately** and are flushed to the database **asynchronously** in
the background.

```text
WRITE update(book):
        SET cache:book:{id} book EX ttl               (immediate, returns fast)
        enqueue(book)                                 (async)
   ...background flusher drains the queue -> db.save(...)   (later, possibly batched)
```

- **Pros:** lowest write latency; can batch/coalesce writes to the DB (great for
  write-heavy, bursty workloads like counters/metrics).
- **Cons:** **durability risk** — data acknowledged but not yet flushed is lost if Redis or
  the app dies before the flush; the DB is eventually consistent with the cache; more moving
  parts (queue, retries, ordering).

### 4. Refresh-Ahead

The cache proactively **refreshes hot entries before they expire**, so reads keep hitting a
warm cache and rarely pay a miss.

```text
READ get(id):
        value, pttl = GET + PTTL cache:book:{id}
        if hit:
            if pttl < refreshThreshold:
                async: reload from db, SET with fresh ttl   (refresh before expiry)
            return value                                     (serve current value now)
        if miss: load from db, cache, return
```

- **Pros:** low and *predictable* read latency for hot keys; avoids the periodic miss spike
  that cache-aside suffers at TTL boundaries.
- **Cons:** refreshes keys that may not be needed again (wasted work); needs a prediction of
  "hot"; added complexity; can hammer the DB if the threshold/concurrency isn't controlled.

### Choosing a Strategy

| Strategy | Write path | Read after write | Best for | Main risk |
|----------|-----------|------------------|----------|-----------|
| Cache-aside | DB, then invalidate | miss (re-loads) | General read-heavy; default choice | Stampede; miss after write |
| Write-through | DB + cache (sync) | hit | Read-after-write consistency matters | Slow writes; caches cold data |
| Write-behind | cache now, DB async | hit | Write-heavy, burst-tolerant | Data loss before flush |
| Refresh-ahead | (reads refresh) | hit | Predictable latency on hot keys | Wasted refreshes; DB load |

Rule of thumb: **start with cache-aside.** Add write-through when read-after-write
consistency matters, write-behind only when write latency/throughput demands it and you can
tolerate loss, and refresh-ahead for a known hot set that must stay warm.

## Cache Invalidation (the hard part)

> "There are only two hard things in Computer Science: cache invalidation and naming
> things." — Phil Karlton

Two mechanisms, used together:

- **TTL (expiry).** Every entry gets a time-to-live so even un-invalidated data self-heals
  eventually. TTL bounds staleness without any write-time coordination. Pick it from how
  stale the data may safely be.
- **Explicit invalidation on write.** On update/delete, remove (or overwrite, for
  write-through) the key so readers don't serve old data until the TTL.

Pitfalls:

- **Update-vs-invalidate race.** Prefer `DEL` over writing the new value in cache-aside;
  writing risks a concurrent reader re-caching the old value. Deleting forces a clean reload.
- **Dual-write inconsistency.** "Write DB then cache" can leave them out of sync if the
  process dies between steps; TTL is the backstop. For stricter needs, use write-through or
  capture changes from the DB (CDC) to invalidate.
- **Negative caching.** Cache "not found" too (briefly) so a missing key doesn't hammer the
  DB on every lookup — but with a short TTL so creates become visible quickly.

## Cache Stampede (thundering herd)

When a hot key expires (or was never cached), **many concurrent requests miss at once** and
all hit the database simultaneously — a stampede that can overwhelm the very DB the cache was
protecting.

This module guards the load path with the **distributed lock**: on a miss, exactly one
request acquires `lock:cacheload:book:{id}`, loads from the DB, and populates the cache; the
others briefly wait and then read the now-warm cache instead of piling onto the DB.

```text
miss -> try acquire lock:cacheload:book:{id}
          acquired -> double-check cache, load from db, SET, release   (single loader)
          not acquired -> wait briefly, re-read cache (served by the winner)
```

Complementary defenses:

- **TTL jitter** — add a small random spread to TTLs so many keys don't expire on the same
  second and stampede together.
- **Refresh-ahead** — refresh before expiry so the miss never happens for hot keys.
- **Stale-while-revalidate** — serve the slightly stale value while one loader refreshes.

## Eviction & Memory

A cache must be allowed to forget. Configure Redis with a memory ceiling and an eviction
policy so it drops cold data instead of running out of memory:

```text
maxmemory 2gb
maxmemory-policy allkeys-lru     # evict least-recently-used across all keys
```

| Policy | Evicts | Use when |
|--------|--------|----------|
| `noeviction` | nothing (writes error) | Redis is a primary store, not a cache |
| `allkeys-lru` / `allkeys-lfu` | any key, least recently / frequently used | Pure cache (most common) |
| `volatile-lru` / `volatile-lfu` / `volatile-ttl` | only keys with a TTL | Mixed cache + persistent keys |

For a dedicated cache, `allkeys-lru` (or `allkeys-lfu` for skewed popularity) plus a
`maxmemory` is the standard. Always set TTLs anyway — eviction is a safety net, not a
strategy.

## Redis Commands

| Command | Role |
|---------|------|
| `SET key value EX ttl` | Populate / write a cache entry with an expiry |
| `GET key` | Read a cache entry (hit/miss) |
| `DEL key` | Invalidate an entry on write |
| `TTL` / `PTTL key` | Remaining lifetime (used by refresh-ahead) |
| `EXPIRE` / `PEXPIRE` | (Re)set an entry's TTL |
| eviction config | `maxmemory`, `maxmemory-policy` bound memory |

## Architecture

```text
                 get/update (per strategy)
                          |
                          v
              +-------------------------+        miss / load (guarded by a lock)
              |   Strategy services     |  ----------------------------+
              | aside / write-through / |                              |
              | write-behind / refresh  |        SET/GET/DEL           v
              +-------------------------+ <------------------>  +---------------+
                          |                                     |     Redis     |
                          | async flush (write-behind)          | cache:book:*  |
                          v                                     +---------------+
                 +-------------------+
                 |  Slow BookDatabase | (system of record: latency + read/write counters)
                 +-------------------+
```

## Time Complexity

| Operation | Complexity |
|-----------|------------|
| Cache `GET`/`SET`/`DEL` | `O(1)` |
| Cache hit (read path) | `O(1)`, sub-millisecond, no DB |
| Cache miss | `O(1)` cache + the DB's cost to load |

The whole point is to move the common case from "DB cost" to "`O(1)` memory cost."

## Common Use Cases

- Read-heavy entity reads (product/user/book by id) — cache-aside.
- Read-after-write surfaces (profile you just edited) — write-through.
- High-frequency counters/metrics flushed in batches — write-behind.
- Hot dashboards / config that must stay warm — refresh-ahead.
- Expensive computed results / API responses — cache-aside with a sensible TTL.

## Run Example

```bash
docker compose up -d
./gradlew bootRun
```

## API Design Note: why `/api/cache`, not `/api/books`

Most modules in this cookbook are resource-first — they root at the domain noun
(`/api/products`, `/api/restaurants`, `/api/order-events`), because in those modules the
*entity* is the subject and Redis is an implementation detail. This module deliberately roots
at `/api/cache` instead, like the [locks](../locks/README.md) module roots at `/api/locks`.

The reason is what the module is *about*. Here the subject is the **caching strategy**, not
the book — `Book` is just the payload that happens to get cached. The whole point is to show
the *same* read/write served five different ways, so the strategy is promoted to a first-class
path segment (`/api/cache/{strategy}/books/{id}`) rather than hidden in a `?strategy=` query
parameter. The cache-level endpoints (`/stats`, `/stats/reset`) also belong to the cache, not
to any book, and have a natural home here.

So the deviation from the resource-first norm is intentional: caching is a *mechanism* module
(like locks), and its path names its subject — the cache and its strategies.

## curl Examples

The `{strategy}` path segment selects the implementation: `aside`, `write-through`,
`write-behind`, `refresh-ahead`, or `stampede`. Every strategy exposes the same two
endpoints — `PUT .../books/{id}` (write) and `GET .../books/{id}` (read) — but behaves
differently. Reset the counters with `POST /api/cache/stats/reset` between experiments so
`/stats` reflects only the calls you care about.

### Cache-aside — read loads on miss, write invalidates

```bash
curl -i -X PUT 'http://localhost:8080/api/cache/aside/books/1' \
  -H 'Content-Type: application/json' \
  -d '{"title":"Redis in Action","author":"Carlson","price":39.99}'

curl -s -X POST 'http://localhost:8080/api/cache/stats/reset'
curl 'http://localhost:8080/api/cache/aside/books/1'   # miss -> slow DB load (databaseReads: 1)
curl 'http://localhost:8080/api/cache/aside/books/1'   # hit  -> from cache (databaseReads still 1)
curl 'http://localhost:8080/api/cache/stats'           # {"databaseReads":1,"databaseWrites":0}

curl -i -X PUT 'http://localhost:8080/api/cache/aside/books/1' \
  -H 'Content-Type: application/json' \
  -d '{"title":"Redis in Action, 2e","author":"Carlson","price":44.99}'
curl 'http://localhost:8080/api/cache/aside/books/1'   # write invalidated -> reload (databaseReads: 2)
```

### Write-through — write populates the cache, so the next read is a hit

```bash
curl -s -X POST 'http://localhost:8080/api/cache/stats/reset'
curl -i -X PUT 'http://localhost:8080/api/cache/write-through/books/2' \
  -H 'Content-Type: application/json' \
  -d '{"title":"Designing Data-Intensive Applications","author":"Kleppmann","price":49.99}'

curl 'http://localhost:8080/api/cache/write-through/books/2'  # already warm -> no DB read
curl 'http://localhost:8080/api/cache/stats'                  # {"databaseReads":0,"databaseWrites":1}
```

### Write-behind — write returns immediately, DB is flushed asynchronously

```bash
curl -s -X POST 'http://localhost:8080/api/cache/stats/reset'
curl -i -X PUT 'http://localhost:8080/api/cache/write-behind/books/3' \
  -H 'Content-Type: application/json' \
  -d '{"title":"Release It!","author":"Nygard","price":34.99}'

curl 'http://localhost:8080/api/cache/stats'   # right away: {"databaseReads":0,"databaseWrites":0}
sleep 1
curl 'http://localhost:8080/api/cache/stats'   # after the flush: {"databaseReads":0,"databaseWrites":1}
```

### Refresh-ahead — reads keep serving while the entry is reloaded before it expires

```bash
curl -s -X POST 'http://localhost:8080/api/cache/stats/reset'
curl -i -X PUT 'http://localhost:8080/api/cache/refresh-ahead/books/4' \
  -H 'Content-Type: application/json' \
  -d '{"title":"The Pragmatic Programmer","author":"Hunt","price":42.00}'

curl 'http://localhost:8080/api/cache/refresh-ahead/books/4'  # served from cache, no reload yet
sleep 2                                                       # entry now within the refresh window (TTL ~3s)
curl 'http://localhost:8080/api/cache/refresh-ahead/books/4'  # still served instantly; triggers async reload
sleep 1
curl 'http://localhost:8080/api/cache/stats'                  # reload happened in the background (databaseReads >= 1)
```

### Stampede protection — concurrent misses load the DB once

```bash
curl -s -X POST 'http://localhost:8080/api/cache/stats/reset'
# Seed the DB, then evict the cache so the next reads are all misses.
curl -s -X PUT 'http://localhost:8080/api/cache/stampede/books/5' \
  -H 'Content-Type: application/json' \
  -d '{"title":"SRE Book","author":"Google","price":0.00}' > /dev/null
docker exec redis-local redis-cli DEL cache:book:5
curl -s -X POST 'http://localhost:8080/api/cache/stats/reset'

# Fire 20 concurrent reads at the cold key.
seq 20 | xargs -P 20 -I{} curl -s 'http://localhost:8080/api/cache/stampede/books/5' > /dev/null
curl 'http://localhost:8080/api/cache/stats'   # one loader won the lock: {"databaseReads":1,...}
```

Inspect the cache directly:

```bash
docker exec redis-local redis-cli GET cache:book:1
docker exec redis-local redis-cli PTTL cache:book:1
```

## Production Considerations

- **Always set a TTL**, even with explicit invalidation — it's the backstop against missed
  invalidations and dual-write drift. Add **jitter** to avoid synchronized expiry.
- **Bound memory** with `maxmemory` + an LRU/LFU policy; never let an unbounded cache OOM.
- **Protect the load path** against stampedes (lock, refresh-ahead, or stale-while-revalidate)
  for hot keys.
- **Pick invalidate-over-update** for cache-aside writes to avoid re-caching stale values.
- **Treat the cache as disposable.** Reads must fall back to the source on a cache outage;
  never make correctness depend on the cache being present.
- **Mind write-behind durability.** Persist the queue or accept possible loss; make flushes
  idempotent and retry-safe.
- **Version your keys/serialization** (e.g. `cache:v2:book:{id}`) so a schema change doesn't
  deserialize old payloads.

## Interview Notes

**What is cache-aside and why invalidate instead of update?**

The app reads cache, loads from the DB on a miss, and on write updates the DB then deletes
the cache key. Deleting (not overwriting) avoids a race where a concurrent reader re-caches
the old value; the next read reloads fresh.

**Difference between write-through and write-behind?**

Write-through writes cache and DB synchronously (fresh, but slow writes). Write-behind writes
the cache immediately and flushes to the DB asynchronously (fast writes, but data can be lost
before the flush and the DB is eventually consistent).

**What is a cache stampede and how do you prevent it?**

When a hot key expires, many requests miss simultaneously and all hit the DB. Prevent it by
letting a single loader repopulate under a lock while others wait, by refreshing ahead of
expiry, by serving stale-while-revalidate, and by adding TTL jitter.

**How do you bound cache memory?**

Set `maxmemory` and a `maxmemory-policy` (typically `allkeys-lru`/`allkeys-lfu` for a pure
cache), and give entries TTLs. Eviction handles the overflow; TTL handles staleness.

**Why add jitter to TTLs?**

So many keys cached together don't all expire on the same second and stampede the DB at once;
a random spread smooths the expiry (and the load).

**How do you keep the cache and database consistent?**

TTL for eventual self-healing, explicit invalidation on write, invalidate-over-update to
avoid races, and for strict needs write-through or change-data-capture driven invalidation.
The cache is best-effort; the database is the source of truth.

**Is `@Cacheable` (Spring Cache) the same thing?**

Spring's `@Cacheable`/`@CacheEvict` is cache-aside under the hood with Redis as the backing
store. It's convenient, but implementing the pattern manually (as here) makes the
hit/miss/invalidate mechanics — and the production pitfalls — explicit.
