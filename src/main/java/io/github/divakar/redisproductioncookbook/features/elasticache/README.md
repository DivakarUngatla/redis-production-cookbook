# Amazon ElastiCache for Redis: Production Setup Guide

## Introduction

Every other module in this cookbook ran Redis **yourself** — you configured
[persistence](../persistence/README.md), wired up [replication](../replication/README.md), added
[Sentinel](../sentinel/README.md) for failover, and sharded with [Cluster](../cluster/README.md).
**Amazon ElastiCache for Redis** is AWS running all of that **for you**: a managed, in-memory
Redis service where AWS handles provisioning, patching, backups, failure detection, and failover,
and you focus on configuration and your application.

This module is the **capstone**: a guide to standing up a *complete, production-grade* Redis on
AWS, mapping everything you already learned to the managed offering — plus copy-paste
**Terraform** and **AWS CLI** examples next to this README.

![Amazon ElastiCache for Redis – Complete Setup & Configuration Guide](/docs/images/redis_aws_elastic_cache.png)

> **Heads-up — these examples are not run live.** Unlike the Docker modules in this cookbook, the
> [Terraform](./terraform/elasticache.tf) and [AWS CLI](./create-elasticache.sh) here can't be
> verified against a real account in this repo. They're well-formed, **placeholder-driven**
> starting points — replace the VPC/subnet/security-group IDs and review before `apply`.

> **Don't skip:** [What you still own](#what-aws-manages-vs-what-you-still-own),
> [the module→feature map](#how-this-maps-to-the-rest-of-the-cookbook), and
> [Network & security](#network-and-security) — the parts people get wrong in real deployments.

## What AWS Manages vs What You Still Own

The whole point of ElastiCache is that AWS takes the operational toil. But "managed" is **not**
"hands-off configuration" — the architecture and safety decisions are still yours.

| AWS manages for you | You still decide / own |
|---------------------|------------------------|
| Provisioning nodes, OS, Redis patching | Node type & size (memory/CPU) |
| Automatic failure detection & failover (Multi-AZ) | Whether to enable Multi-AZ and how many replicas |
| Backups to S3, snapshot scheduling | `maxmemory-policy` and other parameter-group settings |
| Cluster slot management & rebalancing | Cluster mode on/off, number of shards |
| Hardware health, replacement | VPC, subnets, security groups (network reachability) |
| Endpoint DNS that follows failovers | Encryption (TLS, KMS), AUTH token / IAM auth |

> ElastiCache removes the *operations*, not the *design*. A wrong eviction policy, an open
> security group, or a single-AZ deployment is still your problem.

## How This Maps to the Rest of the Cookbook

This is the useful bridge — the concepts you learned by hand are the knobs you set in ElastiCache:

| Cookbook module | ElastiCache equivalent |
|-----------------|------------------------|
| [Persistence](../persistence/README.md) (RDB/AOF) | Automated **backups to S3** (RDB) + optional **AOF** via parameter group |
| [Replication](../replication/README.md) | **Read replicas** (up to 5 per shard) for read scaling & DR |
| [Sentinel](../sentinel/README.md) (auto failover) | **Multi-AZ** — AWS detects primary failure and promotes a replica automatically |
| [Cluster](../cluster/README.md) (sharding) | **Cluster Mode Enabled** — data split across shards (16384 hash slots) |
| Eviction / `maxmemory-policy` | **Parameter group** settings (`maxmemory-policy`, `timeout`, …) |
| [Caching](../caching/README.md) strategies | unchanged — your app code is the same; only the connection endpoint differs |

In short: **Sentinel ≈ Multi-AZ, Cluster ≈ Cluster Mode Enabled, persistence ≈ backups + AOF.**
You don't run those processes — you toggle them.

## Deployment Options

ElastiCache has two topologies, mirroring the standalone-vs-Cluster choice from earlier modules:

| | Cluster Mode **Disabled** | Cluster Mode **Enabled** |
|---|---|---|
| Topology | 1 primary + up to 5 read replicas | up to 90 nodes = N shards × (1 primary + replicas) |
| Data | one full dataset on every node | **sharded** across shards (16384 slots) |
| Scales | reads only | **reads + writes** (add shards) |
| Use when | dataset fits on one node, you need HA + read scale | dataset/throughput outgrows one node |
| Client endpoint | primary endpoint (writes) + reader endpoint (reads) | **configuration endpoint** (cluster-aware client) |

Rule of thumb (same as the cookbook): **fits on one node → Cluster Mode Disabled; needs to grow
beyond one node → Cluster Mode Enabled.**

## Network and Security

This is where most production incidents start. Defaults are *not* safe enough.

- **Launch in a VPC**, in **private subnets across multiple AZs** (via a *subnet group*). Never
  expose Redis to the public internet.
- **Security groups: allow access only from your application's security group** — not `0.0.0.0/0`,
  not a wide CIDR. Redis has no network ACLs of its own; the SG is the perimeter.
- **In-transit encryption: enable TLS.** Clients must connect with TLS (`rediss://`).
- **At-rest encryption: enable KMS.** Encrypts backups and disk.
- **Authentication** — pick one:
  - **AUTH token** — a password set at creation; simple, rotate it periodically.
  - **IAM authentication (recommended)** — short-lived tokens tied to IAM users/roles; no
    long-lived shared secret.
- **Production-grade = TLS + IAM auth + private subnets + tight SG.**

## High Availability: Multi-AZ and Failover

**Multi-AZ** is the managed equivalent of Sentinel/Cluster failover:

- Primary in one AZ, replica(s) in **other AZs**.
- AWS continuously health-checks; on primary failure it **automatically promotes a replica** and
  repoints the endpoint DNS — **no manual intervention, no client reconfiguration**.
- Clients reconnect to the same endpoint and reach the new primary.

```text
Before failover                 After failover
  AZ-a: Primary (M)               AZ-a: (failed)
  AZ-b: Replica (R)  -- sync -->  AZ-b: Primary (R promoted)
  endpoint -> M                   endpoint -> R   (DNS updated by AWS)
```

> Enable Multi-AZ **and** put replicas in different AZs. A replica in the same AZ doesn't protect
> you from an AZ outage. As always, replication is async, so a failover can still lose the last
> few unreplicated writes.

## Cross-Region: Multi-AZ vs Global Datastore

A common misconception: **Multi-AZ does NOT fail over across regions.** It protects against a node
or AZ failure *within one region*. Surviving a whole-region outage (or serving low-latency reads
in other regions) is a different feature — **Global Datastore** — and its regional failover is
**manual**, not automatic.

| | Multi-AZ | Global Datastore |
|---|----------|------------------|
| Scope | one region, across AZs | multiple **regions** |
| Replication | sync-ish within region | **async across regions** (typically <1s lag) |
| Failover | **automatic** (replica promoted, DNS updated) | **manual** — you promote a secondary region |
| Secondary use | replicas serve reads/HA | secondary regions serve **local reads** + DR |
| Protects against | node / AZ outage | **region** outage; global low-latency reads |

```text
Within a region (Multi-AZ)            Across regions (Global Datastore)
  AZ-a Primary --sync--> AZ-b R         us-east-1  Primary  (writes)
  primary dies -> R promoted              |  async replication
  AUTOMATIC, endpoint follows             v
                                        eu-west-1  read-only secondary cluster
                                        region down -> you MANUALLY promote
```

**Why cross-region isn't automatic** (by design): async replication means a forced promotion can
lose recent writes (RPO > 0), inter-region partitions are ambiguous (auto-promoting risks two
primaries / split-brain), and a region cutover is a business/DR decision (DNS, app config,
downstream systems). So AWS leaves the trigger to you. True **active-active multi-region writes**
are not OSS ElastiCache — that's Redis Enterprise CRDTs (see the
[replication module](../replication/README.md)).

### How to set up Global Datastore (cross-region)

A Global Datastore links an existing **primary** replication group with one or more **secondary**
replication groups in other regions. Both must have **encryption in transit enabled** and use
compatible node types/engine versions.

> **The replication itself is automatic.** Once you create the global datastore and attach a
> secondary, **AWS establishes and manages the cross-region replication for you** — initial sync,
> continuous async streaming (typically <1s lag) over AWS's backbone, and read-only enforcement on
> secondaries. You don't configure `replicaof`, VPC peering, or any wiring. The CLI/Terraform
> below just **declares the relationship**; AWS does the plumbing. (Only the *regional failover* is
> manual — see the table above.)

**AWS CLI:**

```bash
# 1) Create the Global Datastore from an EXISTING primary replication group (region A).
aws elasticache create-global-replication-group \
  --region us-east-1 \
  --global-replication-group-id-suffix redis-global \
  --primary-replication-group-id redis-prod

# (note the returned GlobalReplicationGroupId, e.g. "abcd-redis-global")

# 2) Add a SECONDARY replication group in region B, attached to the global datastore.
aws elasticache create-replication-group \
  --region eu-west-1 \
  --replication-group-id redis-prod-eu \
  --replication-group-description "EU secondary for redis-global" \
  --global-replication-group-id "abcd-redis-global" \
  --num-node-groups 3 \
  --transit-encryption-enabled

# 3) Inspect it.
aws elasticache describe-global-replication-groups \
  --global-replication-group-id "abcd-redis-global" \
  --show-member-info
```

**Failing over to another region (manual, deliberate):**

```bash
# Promote the secondary (region B) to be the new primary of the global datastore.
aws elasticache failover-global-replication-group \
  --global-replication-group-id "abcd-redis-global" \
  --primary-region eu-west-1 \
  --primary-replication-group-id redis-prod-eu
```

**Terraform** has matching resources — `aws_elasticache_global_replication_group` (the global
datastore, pointed at a primary `aws_elasticache_replication_group`) plus a second
`aws_elasticache_replication_group` in another region (with a second provider alias) that sets
`global_replication_group_id`. Same shape as the CLI: one global group, one primary, one secondary
per extra region.

> Operational reality: after a regional promotion you also repoint your application (the secondary
> region exposes its own endpoint) and handle the brief write outage during cutover. Practice this
> in a game-day before you need it.

## Persistence and Backups

ElastiCache's durability mirrors the [persistence module](../persistence/README.md):

- **Automated backups (RDB snapshots) to S3** — set a backup window and retention period.
- **Point-in-time snapshots** on demand (e.g., before a risky change).
- **AOF** can be enabled via the parameter group (`appendonly yes`) for finer-grained durability
  — though with Multi-AZ, AWS recommends relying on replicas + backups rather than AOF.
- Enable **both RDB backups and Multi-AZ** for the strongest practical durability.

## Engine and Parameter Configuration

You don't edit `redis.conf` directly — you attach a **parameter group** (a named set of Redis
settings) to the cluster. Common ones:

| Parameter | Purpose | Example |
|-----------|---------|---------|
| `maxmemory-policy` | eviction policy when memory is full | `allkeys-lru` |
| `timeout` | idle client connection timeout (s) | `300` |
| `tcp-keepalive` | keep connections alive | `300` |
| `notify-keyspace-events` | keyspace notifications | `Ex` |
| `cluster-enabled` | sharding on/off (set by topology) | `yes` |
| `appendonly` / `appendfsync` | AOF persistence | `yes` / `everysec` |

> Use a **custom parameter group** in production — don't rely on the defaults. Most parameters
> can be changed live and applied immediately or in the next maintenance window.

## Scaling

- **Vertical (scale up/down)** — change the node type. Brief downtime/failover; pick a node type
  with enough memory headroom (Redis needs spare RAM for forks/replication).
- **Horizontal (scale out)** — Cluster Mode Enabled only: **add shards** and AWS redistributes
  slots; add up to 5 read replicas per shard for read throughput.
- **Auto Discovery** — clients connect to the **configuration endpoint** (a stable DNS name);
  ElastiCache handles node membership changes behind it, so MOVED redirects are handled by
  cluster-aware clients automatically.

## Client Connection

```text
Cluster Mode Disabled:
  primary endpoint   -> writes (and reads)
  reader endpoint    -> load-balanced reads across replicas

Cluster Mode Enabled:
  configuration endpoint -> cluster-aware client discovers all shards, follows MOVED
```

Use a cluster-aware client (Lettuce, Jedis, etc.) in cluster mode, and **always TLS**:

```yaml
# Spring Boot, cluster mode enabled, TLS + AUTH
spring:
  data:
    redis:
      cluster:
        nodes:
          - my-redis-cluster.xxxxxx.clustercfg.use1.cache.amazonaws.com:6379
      ssl:
        enabled: true
      password: ${REDIS_AUTH_TOKEN}   # or use an IAM-generated token
```

## Monitoring and Alerts

Watch these **CloudWatch** metrics and alarm on them:

- **`CPUUtilization`** / **`EngineCPUUtilization`** — high CPU = hot keys or slow commands.
- **`DatabaseMemoryUsagePercentage`** — approaching 100% means evictions or OOM.
- **`Evictions`** — non-zero under an `allkeys-*` policy is normal; spikes mean undersized memory.
- **`CacheMisses`** / hit rate — effectiveness of your caching.
- **`ReplicationLag`** — staleness of read replicas / failover risk.
- **`CurrConnections`** — connection leaks or pool misconfiguration.

Create **CloudWatch alarms** (memory, CPU, replication lag, evictions), enable **enhanced
monitoring**, and route alerts via **SNS**.

## Hands-On: Provisioning a Production Cluster

Two equivalent, copy-paste starting points live next to this README. Both create a **Cluster Mode
Enabled, Multi-AZ, encrypted, authenticated** replication group. Replace the placeholder VPC /
subnet / security-group / KMS IDs first.

- **Terraform (recommended / IaC):** [`terraform/elasticache.tf`](./terraform/elasticache.tf)

  ```bash
  cd src/main/java/io/github/divakar/redisproductioncookbook/features/elasticache/terraform
  terraform init
  terraform plan      # review before applying
  terraform apply
  ```

- **AWS CLI (scripted):** [`create-elasticache.sh`](./create-elasticache.sh)

  ```bash
  cd src/main/java/io/github/divakar/redisproductioncookbook/features/elasticache
  # edit the IDs at the top of the script first, then:
  ./create-elasticache.sh
  ```

> Prefer **IaC (Terraform/CloudFormation)** over click-ops or one-off CLI for anything
> production: it's reviewable, repeatable, and versioned.

## Best Practices Checklist

- [ ] **Cluster Mode Enabled** for large datasets / high write throughput.
- [ ] **Multi-AZ** with replicas **in different AZs** for HA.
- [ ] **Private subnets** only; **security group** scoped to the app SG.
- [ ] **TLS in transit** + **KMS at rest** enabled.
- [ ] **IAM auth** (preferred) or AUTH token, rotated.
- [ ] **Custom parameter group** with an explicit `maxmemory-policy` (don't ship defaults).
- [ ] **Automated backups** + retention; test restores.
- [ ] **CloudWatch alarms** on memory, CPU, replication lag, evictions; SNS notifications.
- [ ] **Provision via IaC**; test failover and restores before go-live.
- [ ] Leave **memory headroom** (don't size to 100%); Redis needs room for forks/replication.

## Important Limits (know these)

- Up to **500 nodes** per Cluster Mode Enabled cluster (soft limit; was historically lower).
- Up to **5 read replicas** per shard.
- **16384 hash slots** (same as open-source Cluster).
- **Number of shards can change** via online resharding, but plan capacity ahead — resharding
  moves data and adds load.

## Interview Notes

**What does ElastiCache give you over self-managed Redis?**

Managed provisioning, patching, backups, monitoring hooks, and — with Multi-AZ — automatic failure
detection and failover with an endpoint that follows the new primary. You trade some control for
removing operational toil; the architecture decisions (topology, eviction, security) are still
yours.

**Cluster Mode Disabled vs Enabled?**

Disabled = one primary + read replicas (one full dataset, read scaling + HA). Enabled = sharded
across shards (16384 slots), scaling reads **and** writes. Same trade-off as Sentinel vs Cluster
in open-source Redis.

**How does failover work, and do clients need to change anything?**

With Multi-AZ, AWS detects a dead primary, promotes a replica in another AZ, and updates the
endpoint DNS. Clients keep using the same endpoint and reconnect — no manual reconfiguration.

**Does ElastiCache fail over across regions automatically?**

No. Multi-AZ auto-failover is **within a region** (across AZs). Cross-region uses **Global
Datastore**, which replicates asynchronously to read-only secondary regions and requires a
**manual promotion** to fail writes over to another region — deliberately, because async
replication + inter-region partitions make automatic cross-region promotion risky (data loss /
split-brain).

**How do you secure a production ElastiCache?**

Private subnets in a VPC, security group scoped to the app, TLS in transit, KMS at rest, and IAM
authentication (or a rotated AUTH token). Never expose it publicly.

**Where do the open-source concepts go?**

Persistence → automated S3 backups (+ optional AOF); replication → read replicas; Sentinel-style
failover → Multi-AZ; Cluster sharding → Cluster Mode Enabled; `maxmemory-policy` and friends →
parameter groups.

**RDB/AOF — do you still configure them?**

You enable automated **backups** (RDB to S3) and can turn on **AOF** via a parameter group, but
with Multi-AZ the common production posture is replicas + scheduled backups rather than AOF.
