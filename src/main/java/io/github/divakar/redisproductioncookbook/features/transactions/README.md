# Redis Transactions: `MULTI`/`EXEC` and Optimistic Locking with `WATCH`

## Introduction

A Redis transaction groups several commands so they execute **atomically** — all of them run
back-to-back with no other client's commands interleaved. You build one with `MULTI` (start),
the commands you want, and `EXEC` (run them all). Add `WATCH` and you get **optimistic
locking**: a check-and-set that retries if someone changed your data underneath you.

This is a deliberately **lightweight** module. Plain `MULTI`/`EXEC` is simple, and for atomic
*read-modify-write with a decision* (counters with caps, locks, rate limits) a
[Lua script](../ratelimiter/README.md) is usually the better tool — which this cookbook
already demonstrates. The one pattern that genuinely belongs to transactions is **`WATCH`-based
optimistic locking**, so the runnable example is exactly that: an atomic **wallet transfer**
(debit one account, credit another) that uses `WATCH` to avoid overdrawing under concurrency.

> **Don't skip:** [No rollback](#no-rollback) and [Transactions vs Lua](#transactions-vs-lua-when-to-use-which)
> — they're the parts people get wrong in interviews.

## What a Redis Transaction Is

Four commands:

| Command | Meaning |
|---------|---------|
| `MULTI` | Start a transaction. Subsequent commands are **queued**, not executed. |
| `EXEC` | Execute all queued commands atomically, return their replies as a list. |
| `DISCARD` | Throw away the queued commands without running them. |
| `WATCH key [key ...]` | Mark keys for optimistic locking *before* `MULTI`. If any change before `EXEC`, `EXEC` aborts. |

```text
MULTI
SET a 1        # queued -> reply is "QUEUED", not the real result
INCR b         # queued
EXEC           # NOW both run atomically; returns [OK, <new b>]
```

The key limitation: once you're in `MULTI`, commands are only **queued**. You **cannot read a
value and branch on it** inside the transaction — the replies don't exist until `EXEC`. Any
"if" logic must happen *before* `MULTI`, which is exactly what `WATCH` is for.

## No Rollback

Redis transactions are **not** like SQL transactions — there is **no rollback**.

- If a command fails to *queue* (e.g. a syntax error), `EXEC` is refused and nothing runs.
- But if a command queues fine and then **fails at `EXEC` time** (e.g. `INCR` on a non-numeric
  value), Redis **runs the rest anyway** and returns the error for that one command. The
  already-applied commands are **not** undone.

So a transaction guarantees **atomic execution** (no interleaving) but **not atomic
correctness on errors**. You're responsible for sending commands that make sense.

## Optimistic Locking with `WATCH`

`WATCH` turns `MULTI`/`EXEC` into a **compare-and-set**. The flow:

```text
WATCH key                 # start watching
value = GET key           # read current state (outside MULTI, so you can see it)
... decide based on value ...
MULTI
SET key <new value>       # queued
EXEC                      # runs ONLY if key was unchanged since WATCH;
                          # returns nil (aborts) if another client touched key
```

If `EXEC` aborts (because a watched key changed), you **retry the whole thing**: re-`WATCH`,
re-read, re-decide. This is *optimistic* — it assumes conflicts are rare and only pays a cost
(a retry) when one actually happens, unlike a pessimistic [distributed lock](../locks/README.md)
that blocks everyone up front.

## The Example: Atomic Wallet Transfer

Moving money between two accounts must be **all-or-nothing** (never debit without crediting)
and must **not overdraw** even if two transfers run at once. Both needs map onto transactions:

```text
retry up to N times:
  WATCH txn:account:{from} txn:account:{to}     # watch both balances
  fromBal = GET txn:account:{from}
  toBal   = GET txn:account:{to}
  if fromBal < amount: UNWATCH; return INSUFFICIENT_FUNDS   # decide before MULTI
  MULTI
  SET txn:account:{from} (fromBal - amount)     # debit  (queued)
  SET txn:account:{to}   (toBal   + amount)     # credit (queued)
  result = EXEC
  if result is nil: continue   # a concurrent transfer changed a balance -> retry
  return OK
```

- **`MULTI`/`EXEC`** makes the debit and credit one atomic step — no state where money has
  left one account but not arrived at the other.
- **`WATCH`** makes the balance check safe: if another transfer changes either balance between
  our read and our `EXEC`, `EXEC` aborts and we retry with fresh balances, so we can never
  overdraw on a stale read.

In Spring Data Redis this runs inside a `SessionCallback` (so `WATCH`, `MULTI`, and `EXEC` all
use the **same connection** — a requirement for transactions). See `WalletService`.

## Transactions vs Lua: when to use which

Both run atomically on the server. The deciding question is **do you need to read a value and
branch on it as part of the atomic operation?**

| | `MULTI`/`EXEC` (+`WATCH`) | Lua (`EVAL`) |
|---|---|---|
| Atomic execution | Yes | Yes |
| Branch on a value read mid-operation | **No** (must decide before `MULTI`) | **Yes** |
| Conditional read-modify-write | via `WATCH` + **app-side retry loop** | natively, **no retry** |
| Round trips | several (`WATCH`/`GET`/`MULTI`/`EXEC`) | one (`EVAL`) |
| Rollback on error | No | No |
| Best for | optimistic CAS where conflicts are rare | the decision must live next to the writes |

Rule of thumb: reach for **Lua** when the logic is "read, decide, then write" and you want it
in one atomic shot (this is most rate-limit / lock / capped-counter work). Reach for
**`WATCH`/`MULTI`/`EXEC`** when you want **optimistic concurrency** — let conflicting writers
retry — and the decision can be made from a snapshot you read up front, like this transfer.

## Redis Commands

| Command | Role |
|---------|------|
| `MULTI` / `EXEC` | Begin / run the queued transaction atomically |
| `DISCARD` | Abandon a queued transaction |
| `WATCH` / `UNWATCH` | Arm / disarm optimistic locking on keys |
| `GET` / `SET` | Read balances (before `MULTI`) and write them (queued in `MULTI`) |

## Architecture

```text
   POST /api/transactions/transfer {from,to,amount}
            |
            v
   +-------------------+   retry on conflict
   |   WalletService   | <-------------------+
   | WATCH/read/decide |                     |
   |  MULTI/EXEC       |    same connection  |
   +-------------------+  (SessionCallback)  |
            |                                |
            v                                |
       +---------+   EXEC aborts (nil) if a  |
       |  Redis  |---- watched balance ------+
       | account |     changed since WATCH
       | strings |
       +---------+
```

## Time Complexity

| Operation | Cost |
|-----------|------|
| `WATCH` / `MULTI` / `EXEC` | `O(1)` plus `O(commands)` queued |
| One transfer | `O(1)` per attempt; total `O(attempts)` under contention |

## Common Use Cases

- Moving a value between two keys atomically (balances, inventory, quotas).
- Compare-and-set on a single key where conflicts are rare (optimistic update).
- Conditional multi-key updates that must not partially apply.
- Anywhere you'd otherwise grab a lock but expect contention to be low — `WATCH` lets writers
  retry instead of blocking.

## Run Example

```bash
docker compose up -d
./gradlew bootRun
```

## curl Examples

```bash
# Seed two accounts
curl -i -X PUT 'http://localhost:8080/api/transactions/accounts/alice' \
  -H 'Content-Type: application/json' -d '{"balance":100}'
curl -i -X PUT 'http://localhost:8080/api/transactions/accounts/bob' \
  -H 'Content-Type: application/json' -d '{"balance":0}'

# Transfer 30 from alice to bob (atomic debit + credit)
curl -i -X POST 'http://localhost:8080/api/transactions/transfer' \
  -H 'Content-Type: application/json' \
  -d '{"from":"alice","to":"bob","amount":30}'
# -> 200 {"status":"OK","fromBalance":70,"toBalance":30,"attempts":1}

# Overdraw is refused (no partial debit happens)
curl -i -X POST 'http://localhost:8080/api/transactions/transfer' \
  -H 'Content-Type: application/json' \
  -d '{"from":"alice","to":"bob","amount":1000}'
# -> 409 {"status":"INSUFFICIENT_FUNDS",...}

curl 'http://localhost:8080/api/transactions/accounts/alice'   # {"id":"alice","balance":70}
```

Watch the state Redis keeps:

```bash
docker exec redis-local redis-cli GET txn:account:alice
docker exec redis-local redis-cli GET txn:account:bob
```

## API Design Note: why `/api/transactions`

Like [caching](../caching/README.md), [locks](../locks/README.md), and
[rate-limit](../ratelimiter/README.md), this is a *mechanism* module — its subject is the Redis
transaction, not the wallet. So it roots at `/api/transactions` with accounts nested beneath
(`/api/transactions/accounts/{id}`, `/api/transactions/transfer`); the wallet is just the
vehicle for showing `WATCH`/`MULTI`/`EXEC`.

## Production Considerations

- **`WATCH`/`MULTI`/`EXEC` must share one connection.** Use a `SessionCallback` (or a
  dedicated bound connection); don't spread them across pooled connections.
- **Always cap retries.** Optimistic locking can livelock under heavy contention; bound the
  attempts and surface a clear failure (or fall back to a pessimistic lock) when exhausted.
- **Prefer Lua for hot conditional RMW.** If a key is extremely hot, `WATCH` retries pile up;
  a single Lua script avoids the retry storm.
- **Remember: no rollback.** Validate inputs before `EXEC`; a runtime error mid-`EXEC` leaves
  prior commands applied.
- **`WATCH` is per-connection and cleared by `EXEC`/`DISCARD`/`UNWATCH`.** Don't assume a watch
  survives across logical operations.

## Interview Notes

**What does a Redis transaction guarantee?**

Atomic *execution*: every queued command between `MULTI` and `EXEC` runs consecutively with no
other client interleaving. It does **not** guarantee rollback on error.

**Is there rollback if one command fails?**

No. A command that fails to queue cancels the whole transaction, but a command that fails at
`EXEC` time doesn't stop or undo the others — Redis has no rollback.

**Why can't you branch inside `MULTI`/`EXEC`?**

Because queued commands return `QUEUED`, not their real results, until `EXEC`. You have no
value to branch on. Branching must happen before `MULTI` — and to make that decision safe under
concurrency you `WATCH` the keys you read.

**How does `WATCH` work?**

It marks keys before the transaction. If any watched key is modified by anyone before your
`EXEC`, the `EXEC` aborts (returns nil) and nothing runs. You then retry: re-watch, re-read,
re-decide. It's optimistic concurrency / compare-and-set.

**Transaction vs Lua script?**

Both are atomic. Use Lua when the atomic step needs to read a value and branch on it (one
round trip, no retries). Use `WATCH`/`MULTI`/`EXEC` for optimistic CAS where conflicts are rare
and you accept retrying on conflict.

**`WATCH`/`MULTI`/`EXEC` vs a distributed lock?**

`WATCH` is *optimistic* (don't block; detect conflict at `EXEC` and retry) — great when
contention is low. A distributed lock is *pessimistic* (block others up front) — better when
contention is high or the critical section is long and non-idempotent.
