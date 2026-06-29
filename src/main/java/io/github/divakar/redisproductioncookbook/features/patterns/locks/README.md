# Redis Distributed Locks: Mutual Exclusion Across Instances

## Introduction

This module implements a **distributed lock** with Spring Boot and Redis: a way to ensure
that, across many application instances, **at most one** holder runs a critical section at a
time. A JVM `synchronized` block or `ReentrantLock` only coordinates threads inside one
process; the moment you run two replicas, they no longer see each other's locks. A
distributed lock moves the mutual-exclusion decision into Redis, which every instance
shares.

This module covers the three lock operations — and a key idea is that they are **not** the
same kind of thing:

- **Acquire a lock with a TTL** — a single atomic command, `SET key token NX PX ttl`. `NX`
  gives mutual exclusion (only one holder wins) and `PX ttl` auto-expires the lock so a
  crashed holder never deadlocks it.
- **Release a lock** — *conditional*: delete it **only if I still own it**. A small **Lua
  script** checks the unique **owner token** first, so you can never release someone else's
  lock.
- **Renew a lock** — *conditional* too: extend the TTL **only if I still own it**, via a Lua
  script. An optional **watchdog** calls renew automatically while a long task runs.

So acquiring is one plain Redis command, while releasing and renewing need scripts because
they must "check ownership, then act" atomically. The module also documents the multi-node
**Redlock** algorithm and the well-known **fencing token** correctness debate, so you know
exactly what guarantees you do — and do not — get.

> **Read this first: a lock in Redis is an *efficiency* tool, not an absolute *correctness*
> guarantee.** Under failover and process pauses, two holders can briefly believe they own
> the same lock. See [Correctness & Failure Modes](#correctness--failure-modes) and
> [Interview Notes](#interview-notes) before relying on it for anything that must never
> double-execute.

## Why a Distributed Lock?

When a service is scaled to N instances, some work must still happen **once at a time**:

- A scheduled job (nightly report, cache refresh) that must not run on every replica
  simultaneously.
- A single-writer section against an external system that can't tolerate concurrent updates.
- "Process this order / charge this card once," guarding a non-idempotent side effect.
- Leader election — one instance becomes the active coordinator.

Redis is a natural home for the lock because every instance already connects to it, it's
fast (sub-millisecond), and `SET ... NX` gives the exact compare-and-set primitive a lock
needs.

## Why Not a Plain `SET` + `DEL`?

A naive lock — `SET key 1` to acquire, `DEL key` to release — is broken in three ways. The
correct pattern fixes each:

| Problem with naive lock | Consequence | Fix in this module |
|-------------------------|-------------|--------------------|
| No expiry | A holder that crashes never releases → permanent deadlock | `PX ttl` so the lock auto-expires |
| `SET` overwrites an existing lock | Two holders at once | `NX` — set only if absent |
| `DEL` deletes whoever's lock is there | You delete *someone else's* lock after yours expired | Unique token + Lua `GET==token then DEL` |

That last point is the subtle one: if your work outruns the TTL, your lock expires, another
instance acquires it, and your `DEL` would then erase **their** lock. The owner token plus
an atomic check-and-delete makes release safe.

## The Locking Protocol

The three operations are **not** the same kind of thing, and that distinction is the heart of
this module:

- **Acquire** is a single, already-atomic Redis command — no script needed.
- **Release** and **renew** are *conditional* ("act only if I still own it") and have no
  single built-in command, so each is a small **Lua script** (explained in the next section).

**Acquire — one atomic command (`SET ... NX PX`):**

```text
SET lock:{name} {token} NX PX {ttl}
  token  = random UUID unique to this holder
  NX     = only set if the key does not exist   (mutual exclusion — only one wins)
  PX ttl = auto-expire after ttl                 (crash safety — no permanent deadlock)
```

`SET` with `NX` is an atomic compare-and-set on its own, so acquiring the lock needs nothing
more. There is no Lua here.

**Release & renew — conditional, so each is a Lua script:**

```text
Release:  EVAL "if redis.call('GET', KEYS[1]) == ARGV[1]
                 then return redis.call('DEL', KEYS[1]) else return 0 end"
            → delete only if we still own it            (safe release)

Renew:    EVAL "if redis.call('GET', KEYS[1]) == ARGV[1]
                 then return redis.call('PEXPIRE', KEYS[1], ARGV[2]) else return 0 end"
            → extend the lease only if we still own it   (watchdog)
```

Both first **check ownership** (does the lock still hold *my* token?) and only then act. That
"check, then act" cannot be two separate commands without a race, which is exactly why they
are scripts — see [Why a Lua script here?](#why-a-lua-script-here) below.

### What is a Lua script (in Redis)?

[Lua](https://www.lua.org/) is a tiny, embeddable scripting language. Redis ships with a Lua
interpreter built in, so you can send a small program to the server with the `EVAL` command
(or `EVALSHA` for a cached script) and Redis runs it **on the server**, next to the data,
rather than in your application. Inside the script you issue normal Redis commands via
`redis.call('GET', ...)`, `redis.call('DEL', ...)`, and so on.

Two properties make this useful for locks:

- **Atomicity.** Redis executes the whole script as a single unit — start to finish with no
  other client's command interleaved (Redis runs commands and scripts on a single thread).
  This lets us bundle several commands into one indivisible operation.
- **One round trip, server-side logic.** The "read a value, decide, then act" logic runs
  where the data lives, so there's no network gap mid-decision and no extra round trips.

Inputs are passed in explicitly: keys as `KEYS[1], KEYS[2], ...` and other arguments as
`ARGV[1], ARGV[2], ...` (Lua arrays are 1-based). Keeping keys in `KEYS` is also what lets
the script work correctly in Redis Cluster. In this module the scripts are defined once as
`RedisScript` constants and run through Spring's `redisTemplate.execute(script, keys, args)`.

### Why a Lua script here?

Acquiring the lock needs no script — `SET key token NX PX ttl` is already a single atomic
command. **Releasing and renewing are the problem**, because they are *conditional*: "delete
(or extend) the lock, but **only if I still own it**." Redis has no single built-in command
for "compare the value, and if it matches, delete/expire." Doing it from the client takes two
commands, and the lock can change between them:

```text
val = GET lock:job                  # step 1: looks like we own it
# ...lock expires here, another instance acquires it...
if val == myToken: DEL lock:job     # step 2: deletes THEIR lock
```

In that gap the lock can expire and be re-acquired by someone else, and your `DEL` would
erase **their** lock (or your `PEXPIRE` would extend a lock you no longer hold). A Lua script
closes the gap: Redis runs it atomically — start to finish with no other command interleaved
— so "check ownership, then act" becomes one indivisible operation and the race disappears.
That is the whole reason release and renew are scripts rather than plain method calls.

### Reading the Lua scripts

The scripts take inputs from the client: `KEYS[1]` is the lock key (e.g.
`lock:inventory-sync`) and `ARGV[1]` is the caller's owner token. Reading the release script
left to right:

- `redis.call('GET', KEYS[1])` — read whoever currently owns the lock.
- `== ARGV[1]` — is it *still my* token?
- if yes → `redis.call('DEL', KEYS[1])` deletes it and returns `1` (keys removed).
- if no → `return 0`: do nothing, because the lock has expired or is now someone else's.

So a return of `1` means "I released it" and `0` means "I no longer owned it" — which is
exactly what the `release(...)`/`renew(...)` methods map to a boolean. The renew script is
identical in shape but takes a second argument `ARGV[2]` (the new TTL in milliseconds) and
calls `PEXPIRE` instead of `DEL` to extend the lease. A `0` from renew tells the watchdog it
has lost the lock.

(Note: if the key is missing, `GET` returns a Lua `false`, which never equals the token
string, so the `else` branch is taken — the missing-lock case is handled for free.)

### The TTL Dilemma and the Watchdog

The TTL is a guess about how long the work takes:

- **Too short** → the lock expires mid-work, a second holder acquires it, and you have two
  holders. (Your safe-release then correctly refuses to delete the new owner's lock.)
- **Too long** → after a crash, the resource stays locked for a long time before recovery.

The **watchdog** resolves the tension: pick a TTL comfortably longer than a single renew
interval, then have a background task call *renew* every `ttl/3` while the work runs, and
stop on completion. The lock lives exactly as long as the holder is alive and working, and
still expires quickly if the holder crashes (no more renewals arrive). This module's
`executeWithLock(...)` runs a watchdog for you.

## Architecture

```text
   instance A        instance B        instance C
        \                |                /
         \               |               /
          \              v              /
           \     SET lock:job NX PX    /
            \           |             /
             v          v            v
           +-----------------------------+
           |            Redis            |
           |   lock:job = {token-A}      |  <- only one wins NX
           +-----------------------------+
                        |
        winner runs the critical section; a watchdog
        renews the lease until it finishes, then the
        Lua release deletes the key (only if still owned)
```

## Redis Commands

| Command | Role |
|---------|------|
| `SET key token NX PX ttl` | Atomic acquire: set-if-absent with an expiry |
| `GET key` | Read the current owner token (inside Lua) |
| `DEL key` | Release (inside the Lua check-and-delete) |
| `PEXPIRE key ttl` | Extend the lease (inside the Lua check-and-renew) |
| `EVAL script` | Run the atomic release/renew Lua scripts |

## Time & Space Complexity

| Operation | Complexity |
|-----------|------------|
| Acquire (`SET NX PX`) | `O(1)` |
| Release / renew (Lua) | `O(1)` |
| Memory per lock | one small key, freed on release or expiry |

Locks are cheap; the cost is entirely in the coordination and the failure semantics, not
the data structure.

## Common Use Cases

- Run a scheduled job on exactly one instance (singleton cron).
- Serialize a non-idempotent side effect ("charge once", "send once").
- Leader election for a coordinator role.
- Throttle access to a rate-limited external API to one caller at a time.
- Guard a migration / one-off task across a rolling deploy.

## How It Works Internally

The lock is just one Redis key whose **value is the owner's token** and whose **TTL** bounds
its lifetime. `SET ... NX` is an atomic compare-and-set: Redis is single-threaded for
command execution, so exactly one concurrent `NX` wins. Release and renew are Lua scripts;
Redis executes a script to completion without interleaving other commands, which makes the
"check I still own it, then act" sequence atomic. Nothing about the lock requires a special
data type — its correctness comes from atomicity (`NX`, Lua) and expiry (`PX`).

## Cluster Considerations

A single lock key lives on **one shard** (the slot of `lock:{name}`), so acquire/release
are ordinary single-key operations and work fine in Redis Cluster. **Redlock is different:**
it is not a cluster feature — it spans **N independent Redis masters** (not replicas, not a
cluster), acquiring the lock on a **majority** to survive the loss of a minority of nodes.
Running Redlock against a single primary-replica pair gives you no extra safety over a
single-node lock.

## Correctness & Failure Modes

A Redis lock is excellent for **efficiency** ("usually only one runs, so we avoid wasted
work") and risky as the **sole** guarantee for **correctness** ("two must *never* run"):

- **Failover.** Redis replication is asynchronous. If a holder acquires the lock on the
  master and the master fails before replicating, the promoted replica has no record of the
  lock, and a second holder can acquire it. Both now believe they hold it.
- **Process pause.** A GC pause or VM stall can freeze a holder past the TTL; the lock
  expires, another holder acquires it, then the first holder wakes up still thinking it owns
  the lock.

The defense for true correctness is a **fencing token**: each successful acquire returns a
monotonically increasing number, the holder passes it to the protected resource, and the
resource **rejects any write with a token lower than the highest it has seen**. A stale
holder's writes are then ignored even if it wrongly believes it holds the lock. Redis locks
don't provide fencing out of the box; if you need it, derive a monotonic token (e.g. an
`INCR` counter) and enforce it at the resource.

### The Redlock Debate (interview gold)

Redlock (by Redis's author) acquires the lock on a majority of N independent masters within
a time budget. Martin Kleppmann's critique ("How to do distributed locking") argues it
still doesn't give correctness under unbounded process pauses and clock skew, and that any
system needing correctness must use fencing tokens — at which point the lock is only an
optimization. Salvatore Sanfilippo's rebuttal defends Redlock's assumptions. The practical
takeaway both sides agree on: **if double-execution would be catastrophic, add fencing (or
use a consensus system like ZooKeeper/etcd); if it would merely be wasteful, a Redis lock is
a fine, fast choice.**

## Scaling Strategies

- **Keep critical sections short.** The lock serializes work; long holds throttle throughput.
- **Size the TTL to the work, and use the watchdog** for variable-length tasks.
- **Prefer many fine-grained locks** (per-entity `lock:order:{id}`) over one global lock to
  preserve concurrency.
- **Make the protected action idempotent** so a rare double-acquire is survivable.
- **Add fencing tokens** when correctness, not just efficiency, is required.

## Run Example

```bash
docker compose up -d
./gradlew bootRun
```

## curl Examples

Acquire a lock (returns a token you must keep to release it):

```bash
curl -i -X POST 'http://localhost:8080/api/locks/inventory-sync/acquire?ttlMillis=10000'
```

```json
{ "key": "inventory-sync", "acquired": true, "token": "b2c1...", "ttlMillis": 10000 }
```

A second acquire while it's held fails:

```bash
curl -i -X POST 'http://localhost:8080/api/locks/inventory-sync/acquire?ttlMillis=10000'
```

```json
{ "key": "inventory-sync", "acquired": false, "token": null, "ttlMillis": 10000 }
```

Release with the token you were given (releasing with the wrong token is refused):

```bash
curl -i -X POST 'http://localhost:8080/api/locks/inventory-sync/release' \
  -H 'Content-Type: application/json' \
  -d '{"token":"b2c1..."}'
```

Run a guarded critical section (acquires, holds for the requested time with the watchdog
renewing the lease, then releases). A concurrent call returns 409 while it's held:

```bash
curl -i -X POST 'http://localhost:8080/api/locks/inventory-sync/run?holdMillis=3000'
```

Inspect the lock directly in Redis:

```bash
docker exec redis-local redis-cli GET lock:inventory-sync
docker exec redis-local redis-cli PTTL lock:inventory-sync
```

## Production Considerations

- **Always set a TTL.** A lock without expiry deadlocks forever if a holder dies.
- **Never release blindly.** Release/renew must verify the token via Lua; never `DEL` by key
  alone.
- **Treat the lock as best-effort.** For must-not-double-execute work, add fencing tokens or
  use a consensus store; keep the protected action idempotent.
- **Mind failover.** Async replication means a freshly acquired lock can be lost on
  promotion; use `WAIT`/`min-replicas-to-write` to narrow (not close) the window.
- **Tune the TTL and watchdog interval together** (renew at ~`ttl/3`) so a renew can be
  retried before expiry.
- **Avoid reentrancy surprises.** This lock is not reentrant; the same holder acquiring twice
  needs explicit support (e.g. a hold count keyed by token).

## Interview Notes

**Why store a unique token instead of a constant value?**

So release is safe. Without a token, any instance's `DEL` could remove a lock acquired by
another instance after the first one's TTL expired. The token lets release verify ownership.

**Why must release and renew be Lua scripts?**

To make "check I own it, then delete/extend" atomic. A separate `GET` then `DEL` has a race:
the lock can expire and be re-acquired between the two commands, and you'd delete the new
owner's lock.

**How do you pick the TTL?**

Long enough to cover normal work, short enough that a crash recovers quickly. For
variable-length work, set a modest TTL and run a watchdog that renews at ~`ttl/3` until the
work completes.

**Does a Redis lock guarantee only one holder ever?**

No. Failover (async replication) and process pauses can produce two holders briefly. It's an
efficiency optimization. For correctness, use fencing tokens or a consensus system.

**What is a fencing token and why does it help?**

A monotonically increasing number returned on acquire and passed to the protected resource,
which rejects any write carrying a token lower than the highest it has seen — so a stale
holder's writes are ignored even if it wrongly thinks it holds the lock.

**When would you use Redlock, and what's the controversy?**

Redlock acquires on a majority of N independent masters for resilience to node loss.
Kleppmann argues it still doesn't ensure correctness under pauses/clock skew and that
fencing is required anyway; antirez disagrees. Use it (or a consensus store) for high-stakes
mutual exclusion; for ordinary "avoid duplicate work," a single-node lock is enough.

**Is this lock reentrant?**

No. Acquiring twice from the same holder would fail the second `NX`. Reentrancy needs an
explicit hold count tied to the owner token.
