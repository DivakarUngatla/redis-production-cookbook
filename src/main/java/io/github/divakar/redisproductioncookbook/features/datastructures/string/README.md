# Redis Strings: Production-Style Session Store

## Introduction

This module implements an expiring user-session store with Spring Boot and Spring
Data Redis. Each session is serialized as one JSON value and stored in a Redis String
under a key such as `session:{abc123}`.

Redis owns the session lifetime. Every session is created with a TTL, can be renewed,
and disappears automatically after expiration.

## Why Redis Strings?

A Redis String is a natural fit when an application reads and writes a session as one
unit. The representation is simple, key lookup is fast, JSON is easy to inspect, and
expiration is attached directly to the session key.

This model works especially well when session objects are small and field-level
updates are uncommon. If individual fields are updated frequently, a Redis Hash may
avoid rewriting the complete JSON document.

## Why Not a Relational Database?

A relational database can store sessions in a table and remove expired rows with a
scheduled cleanup process. That is a valid design and may be preferable when session
state must participate in relational transactions or the existing database already
meets the latency and throughput requirements.

Redis provides in-memory key lookup and native TTL expiration, avoiding repeated
expiration queries and separate cleanup jobs. The trade-off is another stateful
system with memory, persistence, replication, availability, and operational
requirements. Redis is not automatically better than SQL; the choice depends on the
workload and durability contract.

## Redis Key Design

```text
session:{sessionId}
```

Example:

```text
session:{abc123}
```

The value is a JSON document:

```json
{
  "sessionId": "abc123",
  "userId": "user-123",
  "createdAt": "2026-06-20T10:00:00Z",
  "expiresAt": "2026-06-20T11:00:00Z"
}
```

The braces form a Redis Cluster hash tag. The implications are described in
[Cluster Considerations](#cluster-considerations).

## Architecture

```text
                  HTTP requests
                       |
                       v
             +-------------------+
             | SessionController |
             +-------------------+
                       |
                       v
          +-------------------------+
          | UserSessionRepository   |
          +-------------------------+
                       |
                       v
          +-------------------------+
          | Redis String            |
          | session:{sessionId}     |
          |                         |
          | JSON:                   |
          | - sessionId             |
          | - userId                |
          | - createdAt             |
          | - expiresAt             |
          +-------------------------+
```

The controller accepts business-level requests. The repository generates a secure
session ID, serializes the record to JSON, and delegates storage and expiration to
Redis.

## Redis Commands

| Command | Purpose in this module |
|---------|------------------------|
| `SET` | Store the serialized session value |
| `GET` | Retrieve and deserialize a session |
| `SETEX` | Create a session with a mandatory TTL |
| `TTL` | Read the remaining lifetime |
| `EXPIRE` | Renew a session's lifetime |
| `DEL` | Delete a session immediately |
| `EXISTS` | Check whether a session is active |

Spring Data Redis may encode creation as `SET` with an expiration option rather than
the legacy `SETEX` command. Both operations atomically store a value with a TTL.

## Example Commands

Store a JSON session without expiration using `SET`:

```redis
SET session:{abc123} '{"sessionId":"abc123","userId":"user-123","createdAt":"2026-06-20T10:00:00Z","expiresAt":"2026-06-20T11:00:00Z"}'
```

Production sessions in this module always include expiration. Create one for one
hour with `SETEX`:

```redis
SETEX session:{abc123} 3600 '{"sessionId":"abc123","userId":"user-123","createdAt":"2026-06-20T10:00:00Z","expiresAt":"2026-06-20T11:00:00Z"}'
```

Read the session and its remaining lifetime:

```redis
GET session:{abc123}
TTL session:{abc123}
```

Renew the key for another hour:

```redis
EXPIRE session:{abc123} 3600
```

Check for an active session and delete it:

```redis
EXISTS session:{abc123}
DEL session:{abc123}
```

## Time Complexity

| Command | Complexity |
|---------|------------|
| `SET` | `O(1)` |
| `GET` | `O(1)` |
| `DEL` | `O(1)` |
| `EXPIRE` | `O(1)` |
| `TTL` | `O(1)` |
| `EXISTS` | `O(1)` |

These command complexities do not include network transfer or JSON serialization
cost. Very large session values increase memory usage and response time even when
the Redis operation is constant time.

## Common Use Cases

- User sessions
- Authentication tokens
- API keys
- Feature flags
- Configuration values
- Cache entries
- OTP codes

## Expiration and TTL

Redis is widely used for sessions because expiration is a native key property. The
application does not need to scan a session table or run a scheduled deletion job.

The following command stores a session and gives it a one-hour lifetime atomically:

```redis
SETEX session:{abc123} 3600 '{"sessionId":"abc123","userId":"user-123"}'
```

The remaining lifetime can be inspected at any time:

```redis
TTL session:{abc123}
```

When the TTL reaches zero, Redis makes the key unavailable and reclaims it
automatically. `GET` then returns no value and the REST API returns HTTP 404.

This module supports sliding expiration through the extend endpoint. Renewal updates
the JSON `expiresAt` value and the Redis TTL together in an atomic Lua script. The
original `createdAt` remains unchanged, which also makes it possible for a higher
layer to enforce an absolute maximum lifetime.

Redis expiration uses both passive and active techniques:

- Passive expiration removes an expired key when a client attempts to access it.
- Active expiration periodically samples expiring keys and removes keys whose TTLs
  have elapsed.

This combination prevents expired keys from being returned while avoiding a full
keyspace scan for cleanup.

## Cluster Considerations

The key format uses the session ID as a Redis Cluster hash tag:

```text
session:{abc123}
```

Here, `abc123` is the hash tag. Keys containing the same hash tag map to the same
Redis Cluster hash slot. For example:

```text
session:{abc123}
session:metadata:{abc123}
```

Both keys map to the same slot, enabling future multi-key transactions or Lua scripts
for that session. Different session IDs naturally distribute across cluster slots and
shards.

The renewal script in this module operates on one key, so it is safe in Redis Cluster
without additional co-location requirements.

## Scaling Strategies

Redis can manage millions of sessions when capacity is planned around session count,
average JSON size, allocator overhead, replication, and persistence buffers.

- **Expiration cleanup:** spread expiration times with small TTL jitter when many
  sessions are otherwise created and expired simultaneously.
- **Memory management:** monitor `used_memory`, fragmentation, eviction metrics, and
  average session size. Set `maxmemory` and choose an eviction policy deliberately.
- **Hot keys:** use a unique key per session. Avoid shared indexes or counters on the
  critical read path unless they are designed for concentrated traffic.
- **Session sharding:** session IDs distribute keys across Redis Cluster hash slots.
  Add shards as memory or throughput grows and confirm clients follow redirects.
- **Read scaling:** sessions are consistency-sensitive. Replica reads can return stale
  data immediately after creation, renewal, or logout, so primary reads are usually
  safer for authentication decisions.

## Run Example

Start Redis and the application:

```bash
docker compose up -d
./gradlew bootRun
```

The application expects Redis on `localhost:6379` unless connection properties are
overridden.

## curl Examples

Create a one-hour session:

```bash
curl -i -X POST http://localhost:8080/api/sessions \
  -H 'Content-Type: application/json' \
  -d '{"userId":"user-123","ttlSeconds":3600}'
```

The response contains a generated `sessionId`. Use it in subsequent requests:

```bash
SESSION_ID='<sessionId-from-response>'

curl -i "http://localhost:8080/api/sessions/${SESSION_ID}"
curl -i "http://localhost:8080/api/sessions/${SESSION_ID}/ttl"
```

Renew the session for another hour:

```bash
curl -i -X PUT "http://localhost:8080/api/sessions/${SESSION_ID}/extend" \
  -H 'Content-Type: application/json' \
  -d '{"ttlSeconds":3600}'
```

Delete the session during logout:

```bash
curl -i -X DELETE "http://localhost:8080/api/sessions/${SESSION_ID}"
```

Inspect a session directly:

```bash
docker exec redis-local redis-cli GET "session:{${SESSION_ID}}"
docker exec redis-local redis-cli TTL "session:{${SESSION_ID}}"
```

## Production Considerations

- **Session fixation:** issue a new session ID after authentication or privilege
  changes and invalidate the previous session.
- **Secure session IDs:** generate IDs with a cryptographically secure random source
  and enough entropy. This module uses 256 random bits encoded with URL-safe Base64.
- **TTL selection:** balance security, user experience, Redis memory, and application
  risk. Enforce server-side minimum and maximum values rather than trusting clients.
- **Sliding expiration:** renewal improves user experience but can keep a stolen
  session alive. Renew only after legitimate activity and consider rate limiting.
- **Absolute expiration:** enforce a maximum lifetime from `createdAt`, even when
  sliding renewal is enabled, for high-security applications.
- **Session fixation and theft defenses:** use secure, HTTP-only, same-site cookies,
  TLS, CSRF protection, logout invalidation, and optional device or risk checks.
- **Persistence trade-offs:** sessions may tolerate loss after a restart, in which
  case persistence can be reduced. If forced logout is unacceptable, configure and
  test AOF or RDB persistence, replication, failover, backups, and recovery.
- **Eviction:** an eviction policy can remove live sessions before their TTL. Reserve
  capacity and alert on evictions; consider a dedicated Redis deployment.
- **Failure behavior:** decide whether authentication fails closed or uses a limited
  fallback when Redis is unavailable. Configure short timeouts and bounded retries.
- **Sensitive data:** store only required session claims. Avoid secrets and personal
  data that do not need to be present in the session value.

## Interview Notes

**Why use Redis for sessions?**

Redis provides low-latency key lookup, native TTLs, automatic expiration, and shared
state across application instances. It removes the need for sticky load balancing or
an application-managed cleanup job.

**What is the difference between `SET` and `SETEX`?**

`SET` stores a value and can be used without expiration. `SETEX` stores a value and
TTL atomically. Modern `SET` also supports expiration options, such as `EX` and `PX`.

**What happens when a TTL expires?**

The key becomes logically unavailable. Redis removes it during access or active
expiration processing, and subsequent reads behave as though the key does not exist.

**What is the complexity of `GET`?**

`GET` is `O(1)`, excluding network transfer and processing of the returned value.

**How does Redis handle expiration internally?**

Redis stores expiration timestamps separately from values. It checks them during key
access and also runs periodic active-expiration cycles that sample expiring keys.

**What is active versus passive expiration?**

Passive expiration discovers an expired key when a command accesses it. Active
expiration samples and removes expired keys in the background so unused expired keys
do not consume memory indefinitely.

**What happens to sessions after a Redis restart?**

Without persistence, sessions are lost and users must authenticate again. With RDB
or AOF persistence, sessions can be restored, and Redis preserves absolute expiration
times so keys that expired while Redis was offline are not revived as valid sessions.
The exact recovery point depends on the configured persistence and replication
strategy.
