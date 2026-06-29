# Redis Replication: Read Scaling, High Availability, and Consistency

## Introduction

Replication is Redis **copying data from one server (the primary) to one or more copies (the
replicas)**. Every write on the primary is streamed to its replicas so they hold the same data.
It buys you three things:

- **High availability** — if the primary dies, a replica already has the data and can be
  promoted (by Sentinel or Cluster) to take over.
- **Read scaling** — reads can be spread across replicas while writes go to the primary.
- **Disaster recovery** — a replica (often in another region) is a live, off-box copy.

The one thing to internalize up front: **Redis replication is asynchronous.** The primary
acknowledges a write to the client *before* it reaches the replicas, so replicas can briefly lag
and a read from a replica may return slightly **stale** data.

This is a **configuration and operations** topic — it's driven by `redis.conf`/compose and
observed with `redis-cli`. So this module is a guide plus a runnable **primary + 2 replicas**
example.

![Redis Replication – Complete Guide](/docs/images/redis_replication.png)

> **Hands-on:** there's a runnable example below — see
> [Hands-On: One Primary, Two Replicas](#hands-on-one-primary-two-replicas) to spin up a
> primary with two replicas and watch writes propagate.

> **Don't skip:** [Asynchronous flow](#asynchronous-replication-flow),
> [Consistency issues](#consistency-issues), and
> [Replication is not persistence or backup](#replication-vs-persistence-vs-backup) — the parts
> people get wrong in interviews and incidents.

## Asynchronous Replication Flow

```text
1. Client -> SET k v -> Primary
2. Primary -> OK -> Client          (acknowledged immediately, BEFORE replicas)
3. Primary -> streams the write -> Replica 1, Replica 2   (asynchronously)
4. Replicas apply it                (a little later)
```

Because the ACK happens at step 2, there is a window between "the client was told it succeeded"
and "the replicas have it." That window is **replication lag**.

```text
Time:        T1            T2 (+200ms)         T3
Primary:     balance=100   balance=100         balance=100
Replica:     balance=90    balance=90 (stale)  balance=100 (caught up)
```

A read from a replica between T1 and T3 returns the **old** value. Usually milliseconds, but it
is never guaranteed to be zero.

## Full Sync (a new or far-behind replica joins)

When a replica connects fresh (or has fallen too far behind to catch up incrementally), it does
a **full resynchronization** using `PSYNC`:

```text
1. Replica  -> PSYNC ? -1            (I need everything)
2. Primary  -> BGSAVE                (fork, create an RDB snapshot)
3. Primary  -> sends the RDB         -> Replica
4. Replica  -> loads the RDB         (replaces its dataset)
5. Primary  -> streams the writes buffered during the transfer (real-time from here on)
```

After step 5 the replica is caught up and only incremental updates flow. Modern Redis can do
**diskless** full sync (`repl-diskless-sync yes`) — stream the RDB straight over the socket
without writing it to the primary's disk first.

## Partial Sync & the Replication Backlog

A brief network blip shouldn't force an expensive full sync. The primary keeps a **replication
backlog** — a circular in-memory buffer of recent writes:

```text
Replication backlog (circular buffer):  ... SET D  SET E  SET F  (newest)
```

When a replica reconnects, it sends its offset. If the writes it missed are **still in the
backlog**, the primary sends just those (a **partial sync**). If it was disconnected too long
and the data has rolled off the buffer, it falls back to a **full sync**. Size it with
`repl-backlog-size` (bigger buffer tolerates longer disconnects).

## Read Scaling with Replicas

The classic pattern: **writes to the primary, reads from the replicas.**

```text
              writes
   App  ---------------->  Primary
    |                        | replicate
    |  reads                 v
    +-------------->  Replica 1 / Replica 2 (read-only)
```

- Scales reads **horizontally** — add replicas to absorb more read traffic.
- Replicas are **read-only** by default (`replica-read-only yes`); writing to one returns a
  `READONLY` error.
- Great for read-heavy workloads (sessions, catalogs, leaderboards), **as long as the app can
  tolerate slightly stale reads.**

## Consistency Issues

Asynchronous replication means you trade strong consistency for availability and speed. The
failure modes to know:

| Issue | What happens |
|-------|--------------|
| **Stale read** | A read from a lagging replica returns an old value. |
| **Read-after-write** | You write to the primary, then read from a replica that hasn't got it yet — and don't see your own write. |
| **Data loss on failover** | The primary acks a write, then crashes *before* replicating it; a promoted replica never had it. |
| **Eventual consistency** | Replicas converge to the primary's state — eventually, not instantly. |

## Achieving Stronger Consistency (practical knobs)

You can't make async replication strongly consistent, but you can tighten the guarantees:

- **Read from the primary** for read-after-write correctness (give up some read scaling).
- **`WAIT numreplicas timeout`** — block after a write until it's acknowledged by N replicas (or
  the timeout). Turns a write into "replicated to N before I continue," reducing the failover
  data-loss window. It is *not* a full transaction, but it's the main tool.
- **`min-replicas-to-write` / `min-replicas-max-lag`** — refuse writes on the primary unless at
  least N replicas are connected and caught up within a lag bound, so you don't accept writes
  you can't replicate.
- **Sticky routing** — pin a user/session to the primary (or one replica) for a short window so
  they read their own writes.

```conf
# Refuse writes unless >=1 replica is within 10s of lag
min-replicas-to-write 1
min-replicas-max-lag 10
```

## Cross-Region (Global) Replication

Replicas in other regions give low-latency local reads and a disaster-recovery copy — at the
cost of **higher replication lag** (longer network paths) and bandwidth. Reads near a far
replica may be noticeably staler. For DR, a cross-region replica can be promoted if the primary
region goes down.

## Active-Active (Multi-Master) and CRDTs

Standard open-source Redis is **single-primary**: only one node accepts writes; replicas are
read-only. **Active-active** (multiple regions all accepting writes) is **not** supported by OSS
Redis — it requires **Redis Enterprise** with **CRDTs** (Conflict-free Replicated Data Types),
which merge concurrent writes deterministically (e.g. a counter `INCR`'d in two regions sums
correctly). Worth knowing for interviews; out of scope for a plain Redis deployment.

## Replication vs Persistence vs Backup

A trio that's constantly confused — they solve **different** problems:

| | Protects against | Note |
|---|---|---|
| **Replication** | a node going down | a live copy on another node — but it copies *mistakes* too (`DEL` replicates) |
| **[Persistence](../persistence/README.md)** (RDB/AOF) | process restart / crash | reload from local disk |
| **Backup** | disaster / human error / corruption | restore from a point in the past |

Replication is **not a backup**: if you delete or corrupt data on the primary, every replica
faithfully deletes/corrupts it too. You want all three.

## Common Production Topology

```text
                 writes        +-----------+
        App ---------------->  |  Primary  |
         |  reads              +-----------+
         |                       |        |
         v                  replicate   replicate
   +-----------+            +-----------+   +-----------+
   | Replica 1 |  <-------- | Replica 1 |   | Replica 2 |
   +-----------+            +-----------+   +-----------+
                                  ^
                          Sentinel watches & promotes a
                          replica if the primary fails
```

Persistence on at least the primary (and ideally replicas), replicas for read scale + HA, and
Sentinel (or Cluster) for automatic failover.

## Configuration

A replica is just a normal Redis told who its primary is:

```conf
# On the replica:
replicaof primary-host 6379    # follow this primary (was "slaveof" pre-5.0)
replica-read-only yes          # default: reject writes to the replica
```

```yaml
# compose: one primary, two replicas (see compose.replication.yaml)
services:
  primary:
    image: redis:8.2
    ports: ["6390:6379"]
  replica-1:
    image: redis:8.2
    command: ["redis-server", "--replicaof", "primary", "6379"]
    ports: ["6391:6379"]
  replica-2:
    image: redis:8.2
    command: ["redis-server", "--replicaof", "primary", "6379"]
    ports: ["6392:6379"]
```

## Hands-On: One Primary, Two Replicas

A runnable `compose.replication.yaml` lives **next to this README**. It starts a primary
(host port **6390**) and two replicas (**6391**, **6392**) — chosen so they don't clash with the
main `redis-local` (6379) or the persistence demo (6380).

```bash
cd src/main/java/io/github/divakar/redisproductioncookbook/features/replication
docker compose -f compose.replication.yaml up -d

# Write on the PRIMARY, read it back from a REPLICA — replication in action
docker exec redis-primary   redis-cli SET hello world
sleep 1
docker exec redis-replica-1 redis-cli GET hello      # "world"
docker exec redis-replica-2 redis-cli GET hello      # "world"

# Roles and link status
docker exec redis-primary   redis-cli INFO replication   # role:master, connected_slaves:2
docker exec redis-replica-1 redis-cli INFO replication   # role:slave, master_link_status:up

# Replicas are read-only
docker exec redis-replica-1 redis-cli SET x 1            # (error) READONLY ...

# WAIT until a write is acknowledged by both replicas (returns the count, e.g. 2)
docker exec redis-primary   redis-cli SET durable yes
docker exec redis-primary   redis-cli WAIT 2 1000

docker compose -f compose.replication.yaml down
```

## Production Considerations

- **Expect lag; design for stale reads.** Only route reads to replicas where slight staleness is
  acceptable; send read-after-write paths to the primary or use `WAIT`.
- **Replication is not durability or backup.** Keep [persistence](../persistence/README.md) on
  and take off-box backups; replicas copy mistakes.
- **Guard writes** with `min-replicas-to-write` / `min-replicas-max-lag` if losing acked writes
  on failover is unacceptable.
- **Size the backlog** (`repl-backlog-size`) so short disconnects do partial (not full) syncs.
- **Replication alone isn't failover.** You still need Sentinel or Cluster to *detect* a dead
  primary and *promote* a replica automatically.
- **Watch the metrics:** `master_link_status`, `master_repl_offset` vs `slave_repl_offset` (lag),
  `connected_slaves`.

## Interview Notes

**Is Redis replication synchronous or asynchronous?**

Asynchronous by default — the primary acks the client before replicas receive the write, so
replicas can lag and replica reads can be stale. `WAIT` lets you block until N replicas ack.

**What's the difference between full and partial sync?**

Full sync (`PSYNC`) sends an entire RDB snapshot to a fresh or far-behind replica. Partial sync
resends just the recent commands from the replication backlog when a replica briefly disconnects
and reconnects within the buffer window.

**What is the replication backlog?**

A circular in-memory buffer of recent writes on the primary. If a reconnecting replica's missed
writes are still in it, the primary does a cheap partial sync instead of a full resync.

**Can you write to a replica?**

No by default (`replica-read-only yes`) — writes return `READONLY`. Replicas serve reads.

**Does replication replace persistence or backups?**

No. Replication protects against a node failing, but copies mistakes (a `DEL` replicates). You
still need persistence (survive restart) and backups (survive disaster/human error).

**How do you get read-after-write consistency with replicas?**

Read from the primary, use `WAIT` to ensure replication before proceeding, or sticky-route the
client so it reads where its write landed.

**Does open-source Redis support multi-master / active-active?**

No — OSS Redis is single-primary. Active-active with conflict resolution needs Redis Enterprise
CRDTs.

**Replication vs failover?**

Replication keeps copies in sync. Failover (Sentinel/Cluster) is detecting a dead primary and
promoting a replica — replication is the prerequisite, not the failover mechanism itself.
