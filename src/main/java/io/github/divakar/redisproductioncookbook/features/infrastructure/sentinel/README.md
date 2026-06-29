# Redis Sentinel: Automatic Failover and High Availability

## Introduction

[Replication](../replication/README.md) keeps copies of your data, but it does **not** detect a
dead primary or promote a replica — that's manual. **Sentinel** is the piece that makes Redis
**highly available**: a set of small processes that continuously **monitor** your primary and
replicas, **detect** when the primary is down, **agree** on it, **elect a leader**, and
**automatically promote** a replica to be the new primary — then tell clients about the change.

Think of Sentinel as a **watchdog**: *monitor → detect → recover*, with no human in the loop.

This is a **configuration and operations** topic, so this module is a guide plus a runnable
example: **1 primary + 2 replicas + 3 sentinels**, including a real failover you can trigger.

![Redis Sentinel – Made Easy](/docs/images/redis_sentinel_concept.png)

> **Hands-on:** there's a runnable example below — see
> [Hands-On: Watch a Real Failover](#hands-on-watch-a-real-failover) to kill the primary and
> watch Sentinel promote a replica automatically.

> **Don't skip:** [SDOWN vs ODOWN & quorum](#sdown-vs-odown-and-quorum),
> [the failover process](#the-failover-process-step-by-step), and
> [Clients with Sentinel](#clients-with-sentinel) — the core of how (and why) it works.

## What Sentinel Provides

- **Automatic failover** — promotes a replica when the primary fails.
- **Monitoring** — health-checks every primary and replica.
- **Configuration provider** — clients ask Sentinel "who is the current primary?" instead of
  hardcoding an address, so they follow failovers automatically.
- **Notification** — can alert / run scripts on events.

Sentinel provides **HA, not sharding.** For horizontal scale (splitting data across nodes) you
want [Cluster](../cluster/README.md).

## Architecture

```text
        Sentinel 1  <----->  Sentinel 2  <----->  Sentinel 3
            \                   |                   /
             \   monitor (PING) | monitor          /
              v                 v                 v
                          +-----------+
                          |  Primary  |
                          +-----------+
                            |        |
                       replicate   replicate
                     +-----------+   +-----------+
                     | Replica 1 |   | Replica 2 |
                     +-----------+   +-----------+
```

Typical setup: **an odd number of Sentinels (3, 5, or 7)** so a majority can be formed,
**1 primary**, and **N replicas**. The odd count is about **quorum** — see below.

## How Sentinel Works (overview)

1. **Monitor** — Sentinels `PING` every Redis node periodically.
2. **Detect** — if a node doesn't respond within `down-after-milliseconds`, that Sentinel marks
   it **subjectively down (SDOWN)**.
3. **Agree** — Sentinels share what they see. If at least **quorum** Sentinels agree the primary
   is down, it becomes **objectively down (ODOWN)**.
4. **Elect a leader** — the Sentinels elect one leader (a majority vote) to run the failover.
5. **Failover** — the leader promotes a suitable replica to primary, reconfigures the other
   replicas to follow it, and clients are notified of the new primary.

## SDOWN vs ODOWN and Quorum

This distinction is the heart of Sentinel:

- **SDOWN (Subjectively Down)** — *one* Sentinel thinks the primary is down (it stopped getting
  PONGs). One opinion isn't enough — that Sentinel might just have a network problem.
- **ODOWN (Objectively Down)** — **quorum** Sentinels independently report SDOWN, so they
  collectively agree it's really down. Only ODOWN triggers a failover.

```text
sentinel monitor mymaster <ip> 6379 2
                                     ^ quorum = 2 Sentinels must agree to declare ODOWN
```

Two separate numbers matter:

- **Quorum** — how many Sentinels must agree the primary is down to *start* a failover.
- **Majority** — to actually *perform* the failover, a majority of the total Sentinels must be
  reachable to elect a leader (this is why you run an odd number, and why a quorum smaller than
  majority still needs majority to act).

With 3 Sentinels and quorum 2: 2 must agree it's down, and 2 (majority) must be alive to elect a
leader and proceed.

## Communication Between Sentinels

Sentinels find each other and share state two ways:

- **Pub/Sub discovery** — every Sentinel publishes its presence and view to the
  `__sentinel__:hello` channel on the monitored Redis nodes; others subscribe and learn about
  new Sentinels and the current config. This is how you only configure each Sentinel with the
  *primary* — they auto-discover the replicas and each other.
- **Direct TCP** — Sentinels talk to each other directly to exchange state, vote, and elect a
  leader during failover.

The hello message carries the Sentinel's id, IP/port, the master name, and a **config epoch**
(a version number used to agree on the latest configuration).

## The Failover Process (step by step)

```text
1. Primary Down       Sentinel 1 stops getting PONG from the primary.
2. Subjectively Down  Sentinel 1 marks the primary SDOWN.
3. Objectively Down   Quorum Sentinels agree -> ODOWN.
4. Elect Leader       Sentinels vote; one leader is elected to run the failover.
5. Failover           Leader promotes a replica (say R1) to primary,
                      points R2 at R1, and notifies clients of the new primary.
```

Replica selection prefers the most up-to-date, healthy replica (lowest priority number wins;
`replica-priority 0` makes a replica never eligible).

## Clients with Sentinel

A Sentinel-aware client does **not** hardcode the primary's address. Instead it:

1. Asks the Sentinels: *"who is the current primary for `mymaster`?"*
2. Connects to whatever they return.
3. On failover, the Sentinels report the new primary, and the client reconnects there
   automatically.

```text
   App ---- "who is primary for mymaster?" ---->  Sentinel 1 / 2 / 3
   App <---- "10.0.0.11:6379" --------------------/
   App ----> connects to 10.0.0.11:6379 (current primary)
```

Spring Boot / Lettuce is Sentinel-aware — you point it at the Sentinels, not the primary:

```yaml
spring:
  data:
    redis:
      sentinel:
        master: mymaster
        nodes:
          - localhost:26379
          - localhost:26380
          - localhost:26381
```

The client library handles discovery and reconnection on failover; your application code is
unchanged. Use a **Sentinel-aware library** (Lettuce, Jedis) — a plain client pointed at a fixed
host won't follow a failover.

## Sentinel Configuration

```conf
port 26379
sentinel resolve-hostnames yes                 # allow using hostnames (handy in Docker)
sentinel monitor mymaster <primary> 6379 2     # name, host, port, quorum
sentinel down-after-milliseconds mymaster 5000 # no PONG for 5s -> SDOWN
sentinel failover-timeout mymaster 60000       # max time for a failover attempt
sentinel parallel-syncs mymaster 1             # how many replicas resync at once after failover
```

| Directive | Meaning |
|-----------|---------|
| `sentinel monitor name host port quorum` | which primary to watch and how many Sentinels must agree on down |
| `down-after-milliseconds` | how long without a PONG before SDOWN |
| `failover-timeout` | bound on a failover attempt before retrying |
| `parallel-syncs` | replicas reconfigured to the new primary in parallel (lower = less load, slower) |

> Sentinel **rewrites its own config file** at runtime (to record discovered nodes and the
> config epoch), so its config must be writable — you can't share one read-only file across
> Sentinels.

## Hands-On: Watch a Real Failover

A runnable `compose.sentinel.yaml` lives **next to this README**: 1 primary, 2 replicas, and 3
Sentinels. Host ports avoid the other demos (primary **6400**, replicas **6401/6402**, sentinels
**26379/26380/26381**).

```bash
cd src/main/java/io/github/divakar/redisproductioncookbook/features/sentinel
docker compose -f compose.sentinel.yaml up -d
sleep 5

# Who do the Sentinels currently consider the primary?
docker exec redis-sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster
docker exec redis-sentinel-1 redis-cli -p 26379 SENTINEL master mymaster | grep -A1 -E 'num-slaves|num-other-sentinels'

# Kill the primary to trigger a failover
docker stop redis-primary

# Wait past down-after (5s) + election, then ask again -> a REPLICA has been promoted
sleep 12
docker exec redis-sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster

# The promoted node now reports role:master
docker exec redis-replica-1 redis-cli INFO replication | grep -E 'role:'
docker exec redis-replica-2 redis-cli INFO replication | grep -E 'role:'

# (Optional) bring the old primary back. It starts up as a master, then Sentinel
# notices it and reconfigures it to replicate from the NEW primary (give it ~30s).
docker start redis-primary
sleep 30
docker exec redis-primary redis-cli INFO replication | grep -E 'role:|master_host:'

docker compose -f compose.sentinel.yaml down
```

You'll see `get-master-addr-by-name` return the old primary first, then a replica's address after
the failover — Sentinel detected the outage and recovered with no human action.

## Sentinel vs Replication vs Cluster

| Feature | Replication only | Replication + Sentinel | Redis Cluster |
|---------|------------------|------------------------|---------------|
| Purpose | data redundancy | **high availability** | horizontal scaling (sharding) |
| Write master | 1 (manual failover) | 1 (**automatic** failover) | many (one per shard) |
| Failover | manual | **automatic** | automatic (per shard) |
| Data | full copy on replicas | full copy on replicas | **sharded** across nodes |
| Scales | reads only | reads only | **reads + writes** |
| Components | primary + replicas | primary + replicas + 3–5 Sentinels | cluster nodes (multi-primary) |

Sentinel adds **automatic failover** to a replicated setup; it does **not** shard data. When one
primary can't hold the dataset or the write throughput, you need Cluster.

## Production Best Practices

- **Odd number of Sentinels (3 or 5)** so a majority can always be formed.
- **Run Sentinels on separate machines/failure domains** from each other and from the Redis
  nodes — Sentinels colocated with the node they watch die together.
- **Use Sentinel-aware clients** so apps discover the new primary automatically.
- **Tune `down-after-milliseconds` and `failover-timeout`** to your network: too low causes
  false failovers on a blip; too high delays recovery.
- **Keep inter-node latency low**; Sentinel decisions assume timely PINGs.
- **Sentinel is HA, not scale.** Use [Cluster](../cluster/README.md) for horizontal scaling.

## Interview Notes

**What does Sentinel do?**

Monitors Redis nodes, detects primary failure, elects a leader Sentinel, automatically promotes
a replica to primary, reconfigures the rest, and notifies clients. It's automatic failover +
monitoring + a configuration provider for clients.

**SDOWN vs ODOWN?**

SDOWN is one Sentinel's local opinion that a node is down (missed PONGs). ODOWN is when quorum
Sentinels agree — only ODOWN triggers failover.

**What is quorum, and why an odd number of Sentinels?**

Quorum is how many Sentinels must agree the primary is down to start a failover. A majority of
Sentinels must also be reachable to elect a leader and act — an odd count avoids ties and makes
majorities clean.

**How do clients find the primary after failover?**

Sentinel-aware clients ask the Sentinels for the current primary (and subscribe to change
events), then reconnect to the new primary automatically — no hardcoded address.

**How do Sentinels discover each other and the replicas?**

Via the `__sentinel__:hello` Pub/Sub channel on the monitored nodes (plus direct TCP). You only
configure the primary; replicas and other Sentinels are auto-discovered.

**Sentinel vs Cluster?**

Sentinel gives high availability for a single primary (with replicas) but no sharding. Cluster
shards data across multiple primaries and provides per-shard failover — use it for horizontal
read+write scaling.

**Does Sentinel prevent data loss?**

No, not fully. Replication is asynchronous, so writes acked by the old primary but not yet
replicated can be lost on failover. Reduce the window with `min-replicas-to-write`/`WAIT`, but
HA is about availability, not zero loss.
