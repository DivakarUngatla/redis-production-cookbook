# Redis Rate Limiting: Four Algorithms, One Atomic Script Each

## Introduction

Rate limiting protects a service from abuse and overload by capping how many requests a
client may make in a window of time. Redis is the natural home for it: the counter has to be
**shared across every application instance** (a per-process counter limits nothing in a fleet
of pods) and the check has to be **fast** (it runs on every request). Redis gives you a
single, in-memory, atomic counter that all instances share.

This module is deliberately scoped to **how Redis implements rate limiting**, not to building
a full rate-limiter product. There is no API-gateway integration, no dynamic per-route config
store, no multi-region quota reconciliation. Instead it implements the four classic algorithms
— **fixed window, sliding window log, sliding window counter, and token bucket** — each as a
single **Lua script** so the read-modify-write is atomic, and compares their trade-offs.

> **Don't skip:** the [algorithm comparison](#choosing-an-algorithm) and [why a Lua script](#why-a-lua-script)
> sections — that's the production and interview substance. Like the
> [locks](../locks/README.md) module, the focus is the *protocol*, not a framework around it.

## Why a Lua Script?

Every rate-limit check is a **read-modify-write**: read the current count, decide if the
request fits, then write the new count. Doing that as separate Redis calls from the
application is a race condition — between your `GET` and your `INCR`, another instance can run
the same check, and both let a request through that should have been the one over the limit.

Redis runs a Lua script **atomically**: no other command interleaves while it executes. So the
entire decision — read the counter/bucket, evaluate the limit, update state, compute the
answer — happens as one indivisible step. Every algorithm here is therefore one `EVAL`,
returning `{allowed, remaining, retryAfterMillis}` in a single round trip. (See the locks
module for a deeper primer on Lua in Redis.)

A note on time: the sliding-log, sliding-counter, and token-bucket algorithms need "now." This
module passes the application's clock into the script as an argument for simplicity. In a fleet
with clock skew you'd instead use Redis's own `TIME` command inside the script so every
instance shares one clock.

## The Four Algorithms

Each algorithm is just **a way of counting requests with Redis**. They differ in *what* they
store (a number, a list of timestamps, or a bucket of tokens) and *how* they decide. The
examples below all use **"5 requests per 10 seconds."**

### 1. Fixed Window — one counter that resets each window

**Idea:** chop time into fixed 10-second blocks and keep one counter per block. Count
requests; when the block ends, the counter resets to zero.

**How Redis does it:**

- `INCR rl:fw:{key}` — add 1 to the counter (Redis creates it at `1` if it doesn't exist).
- On the **first** request only, `PEXPIRE rl:fw:{key} 10s` — tell Redis to auto-delete the
  counter after 10s. That auto-delete *is* the window reset.
- If the counter is now greater than the limit, reject; otherwise allow.

**Walkthrough:** requests 1–5 → counter goes `1,2,3,4,5`, all allowed. Request 6 → counter
`6`, over 5 → **blocked**. After 10s the key expires; the next request starts again at `1`.

- **Pros:** dead simple, one number, `O(1)` memory.
- **Cons:** **boundary burst** — 5 requests at `00:09` and 5 more at `00:11` are in two
  different blocks, so 10 requests sail through in ~2 seconds.

### 2. Sliding Window Log — a timestamped guest list

**Idea:** write down the exact time of every request, and at any moment count only the ones
from the last 10 seconds. The window truly "slides" with the clock.

**How Redis does it** (a sorted set where each entry's *score* is its timestamp):

- `ZREMRANGEBYSCORE rl:swl:{key} 0 (now-10s)` — delete entries older than 10s (they've aged
  out of the window).
- `ZCARD rl:swl:{key}` — count what's left = requests in the last 10s.
- If under the limit, `ZADD rl:swl:{key} now <id>` — record this request.

**Walkthrough:** if 5 timestamps from the last 10s are already stored, a 6th request counts 5
and is **blocked**. Once the oldest timestamp becomes older than 10s, it's evicted and a slot
frees up — no hard "reset" moment, it just slides.

- **Pros:** **exact** — no boundary burst.
- **Cons:** stores **one entry per request**, so memory grows with traffic. The priciest
  option at high volume.

### 3. Sliding Window Counter — two counters, blended

**Idea:** get most of the sliding-window accuracy without storing every request. Keep just
**two numbers**: the count for the current 10s block and the previous one. As the current
block progresses, the previous block's count "fades out."

**How Redis does it:** read both counters with `GET`, blend them by how much of the previous
window still overlaps the rolling window, and `INCR` the current one if there's room:

```text
overlap  = fraction of the previous window still inside the rolling window
estimate = prevCount × overlap + currCount
allow if estimate < limit
```

**Walkthrough:** previous block had 4 requests, and we're 30% into the current block (so 70%
of the previous block still counts). With 1 request so far this block:
`estimate = 4 × 0.7 + 1 = 3.8` → under 5 → **allowed**. As time passes, `overlap` shrinks
toward 0 and the old count stops mattering — that's what smooths the boundary burst.

- **Pros:** only **two counters**, `O(1)`, and smooths the burst.
- **Cons:** an **approximation** (it assumes last window's requests were evenly spread). This
  is what most production limiters use.

### 4. Token Bucket — a bucket that refills over time

**Idea:** a bucket holds up to `capacity` tokens (here 5). Each request spends one token.
Tokens **drip back** at a steady rate (5 per 10s = 1 every 2s). Empty bucket → rejected. This
is the one that allows a **burst** (spend all 5 at once) but then paces you to the drip rate.

**How Redis does it** (a hash storing `tokens` and `ts` = last-seen time):

- `HMGET rl:tb:{key} tokens ts` — read how many tokens were left and when we last looked.
- Refill for the time since then: `tokens = min(capacity, tokens + elapsed × dripRate)`.
- If `tokens >= 1`, subtract one and allow; else reject. `HSET` the new `tokens`/`ts` back.

**Walkthrough:** start full at 5. Five quick requests drain it to 0 (all allowed = the burst).
A 6th immediately → 0 tokens → **blocked**. Wait 4s → ~2 tokens dripped back → 2 more allowed.

- **Pros:** allows **controlled bursts** up to `capacity` while holding the long-run average;
  `O(1)`. The most common production choice (AWS, Stripe, etc.).
- **Cons:** two knobs to reason about (capacity vs. drip rate); bursts up to capacity are
  by design, so callers must expect them.

### Choosing an Algorithm

| Algorithm | State | Memory | Accuracy | Bursts | Notes |
|-----------|-------|--------|----------|--------|-------|
| Fixed window | one counter | `O(1)` | boundary burst | up to `2×limit` at edges | simplest |
| Sliding log | ZSET of timestamps | `O(requests)` | exact | none | precise but memory-heavy |
| Sliding counter | two counters | `O(1)` | approximate | smoothed | best general default |
| Token bucket | hash (tokens, ts) | `O(1)` | exact average | up to `capacity` | allows intentional bursts |

Rule of thumb: **sliding window counter** for a simple "N requests per window" cap with cheap,
smooth behavior; **token bucket** when you want to permit bursts up to a ceiling while
bounding the sustained rate; **fixed window** only when the boundary burst doesn't matter;
**sliding log** only when you need exactness and traffic is low enough to afford the memory.

## Redis Commands

| Command | Used by |
|---------|---------|
| `INCR`, `PEXPIRE`, `PTTL` | fixed window |
| `ZADD`, `ZREMRANGEBYSCORE`, `ZCARD`, `ZRANGE` | sliding window log |
| `GET`, `INCR`, `PEXPIRE` (two keys) | sliding window counter |
| `HMGET`, `HSET`, `PEXPIRE` | token bucket |
| `EVAL` | all — each algorithm is one atomic script |

## Architecture

```text
   request (client id = {key})
            |
            v
   POST /api/rate-limit/{algorithm}/{key}?limit=&windowMillis=
            |
            v
   +------------------------+      one atomic EVAL
   |  RateLimiter (per algo)| ---------------------------+
   |  fixed / sliding-log / |                            |
   |  sliding-counter / TB  |     {allowed, remaining,   v
   +------------------------+      retryAfterMillis}  +---------+
            |                                          |  Redis  |
            v                                          | counter |
   200 OK            429 Too Many Requests             | / zset  |
   X-RateLimit-*     Retry-After + X-RateLimit-*       | / hash  |
                                                       +---------+
```

## Time Complexity

| Algorithm | Per request |
|-----------|-------------|
| Fixed window | `O(1)` |
| Sliding window log | `O(log N + M)` — `ZADD`/`ZREMRANGEBYSCORE` over `N` entries, `M` evicted |
| Sliding window counter | `O(1)` |
| Token bucket | `O(1)` |

## Common Use Cases

- Per-user / per-IP API quotas ("100 requests/minute").
- Login and OTP throttling to slow brute-force attacks.
- Protecting an expensive downstream (search, payments) from spikes.
- Fair-use limits per API key / tier.
- Burst-tolerant limits for batch clients (token bucket).

## Run Example

```bash
docker compose up -d
./gradlew bootRun
```

## curl Examples

The `{algorithm}` path segment selects the implementation: `fixed-window`, `sliding-log`,
`sliding-counter`, or `token-bucket`. `{key}` is the client identity (user id, API key, IP).

```bash
# Allow 5 requests per 10s for user-42 under the token bucket.
# The first 5 return 200; the 6th returns 429 with Retry-After.
for i in $(seq 1 6); do
  curl -i -s -X POST \
    'http://localhost:8080/api/rate-limit/token-bucket/user-42?limit=5&windowMillis=10000' \
    | grep -E 'HTTP/|X-RateLimit-Remaining|Retry-After'
  echo
done
```

```text
HTTP/1.1 200    X-RateLimit-Remaining: 4
HTTP/1.1 200    X-RateLimit-Remaining: 3
HTTP/1.1 200    X-RateLimit-Remaining: 2
HTTP/1.1 200    X-RateLimit-Remaining: 1
HTTP/1.1 200    X-RateLimit-Remaining: 0
HTTP/1.1 429    Retry-After: 2    X-RateLimit-Remaining: 0
```

Swap the algorithm to compare behavior at the window boundary:

```bash
curl -i -s -X POST 'http://localhost:8080/api/rate-limit/fixed-window/user-42?limit=5&windowMillis=10000'
curl -i -s -X POST 'http://localhost:8080/api/rate-limit/sliding-counter/user-42?limit=5&windowMillis=10000'
curl -i -s -X POST 'http://localhost:8080/api/rate-limit/sliding-log/user-42?limit=5&windowMillis=10000'
```

Inspect the state Redis keeps for each:

```bash
docker exec redis-local redis-cli GET   rl:fw:user-42      # fixed window counter
docker exec redis-local redis-cli ZCARD rl:swl:user-42     # sliding log size
docker exec redis-local redis-cli HGETALL rl:tb:user-42    # token bucket state
```

## API Design Note: why `/api/rate-limit`

Like [caching](../caching/README.md) and [locks](../locks/README.md), this is a *mechanism*
module: its subject is the rate-limiting algorithm, not a domain resource. So it roots at
`/api/rate-limit/{algorithm}/{key}` — the algorithm is a first-class, browsable path segment
because comparing algorithms is the whole point — rather than burying it in a query parameter
under some resource path.

## Production Considerations

- **Run the decision in one atomic script.** Never read-modify-write the counter from the app;
  that's the race the Lua script exists to remove.
- **Always set a TTL** on rate-limit keys so idle clients' state is reclaimed and the keyspace
  doesn't grow without bound.
- **Use a single clock.** Across instances, prefer Redis `TIME` inside the script over each
  app server's wall clock to avoid skew widening or narrowing the window.
- **Return the right signals.** `429 Too Many Requests` with `Retry-After` and
  `X-RateLimit-Limit/Remaining/Reset` headers so well-behaved clients can back off.
- **Fail open or closed deliberately.** Decide what happens if Redis is unavailable — usually
  *fail open* (allow traffic) for availability, but *fail closed* for security-critical limits
  like login throttling.
- **Pick the granularity of the key.** Per-user, per-IP, per-API-key, or per-route — and beware
  shared NATs making many users look like one IP.
- **Mind hot keys.** A single very hot limiter key is a single Redis slot; for extreme scale,
  shard the key or use a local pre-filter in front of Redis.

## Interview Notes

**Why use Redis for rate limiting instead of an in-process counter?**

Because the limit must hold across every instance of the service. An in-process counter only
limits one process; behind a load balancer, N processes would allow N× the limit. Redis is a
shared, fast, atomic counter all instances consult.

**Why does the check need to be atomic / a Lua script?**

The check is a read-modify-write. Done as separate commands, two concurrent requests can both
read an under-limit count and both be admitted. A Lua script executes atomically, so the read,
the decision, and the update are one indivisible step.

**What's wrong with a fixed window?**

The boundary burst: a client can send a full `limit` at the end of one window and another full
`limit` at the start of the next — `2×limit` in a moment. Sliding window (log or counter) fixes
this.

**Fixed window vs. sliding window log vs. sliding window counter?**

Fixed window is one counter (cheap, but bursty at edges). Sliding log stores a timestamp per
request (exact, but memory grows with traffic). Sliding counter keeps two fixed-window counters
and weights the previous one by overlap (cheap and smooth, but approximate) — the usual
production pick.

**When would you choose a token bucket?**

When you want to allow bursts up to a capacity while bounding the sustained average rate.
Tokens refill at a fixed rate; a burst drains the bucket, then the client is paced by the
refill. It's the most common production algorithm.

**What should happen if Redis is down?**

A deliberate fail-open or fail-closed decision. Most APIs fail open (serve traffic, lose
limiting) to protect availability; security-sensitive limits (e.g. login attempts) fail closed.
