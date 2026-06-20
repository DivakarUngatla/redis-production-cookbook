# Redis Production Cookbook

Production-grade Redis patterns using Spring Boot, Redis Stack, Streams, Search, Geo, Sentinel, Cluster, and AWS ElastiCache.

Copyright (c) 2026 Divakar Ungatla

---

## Introduction

Redis is much more than a cache. Modern applications use Redis for caching, messaging, stream processing, search, geospatial queries, rate limiting, distributed locking, and real-time analytics.

This repository is a production-focused Redis cookbook built using Spring Boot and Redis. It demonstrates:

- How Redis features work internally
- How to use Redis from Java and Spring Boot applications
- Production deployment patterns
- Redis Cluster and Sentinel architectures
- AWS ElastiCache configurations
- Performance and scalability considerations
- Interview-focused explanations and examples

The goal is to provide practical examples, architectural guidance, production best practices, and interview-focused explanations in a single place.

---

## Learning Path

The repository is organized from foundational concepts to advanced production topics.

### 1. Data Structures

- String
- Hash
- List
- Set
- Sorted Set
- Bitmap
- HyperLogLog
- Geospatial Indexes

### 2. Caching Patterns

- Cache Aside
- Write Through
- Write Behind
- Refresh Ahead

### 3. Messaging

- Pub/Sub
- Streams
- Consumer Groups

### 4. Search

- Redis Search
- Full Text Search
- Ranking and BM25

### 5. Distributed Systems

- Replication
- Sentinel
- Cluster

### 6. Cloud Deployments

- AWS ElastiCache
- Cluster Mode Enabled
- Cluster Mode Disabled

---

## Architecture

```text
                    Spring Boot Application
                               |
                               v
                          Redis Client
                               |
    ----------------------------------------------------------------
    |               |               |               |               |
 Data Structures   Cache         Streams         Search           Geo
    |               |               |               |               |
 Strings          Cache Aside    Consumer       Full Text      Nearby
 Hashes           Rate Limiters  Groups         Search         Queries
 Sorted Sets                                      BM25

                               |
                -------------------------------------
                |                                   |
          Replication                         Cluster
                |                                   |
            Sentinel                         Hash Slots
                                                Sharding

                               |
                       AWS ElastiCache
```

---

## Modules

| Module | Description |
|----------|-------------|
| Data Structures | Redis core data structures and production use cases |
| Caching | Cache Aside, Write Through, Write Behind, Refresh Ahead |
| Pub/Sub | Real-time publish-subscribe messaging |
| Streams | Reliable event processing using consumer groups |
| Search | Full-text search, indexing, ranking, and BM25 |
| Geo | Geospatial indexing and nearby searches |
| Rate Limiter | Fixed Window, Sliding Window, Token Bucket implementations |
| Distributed Locks | Redis-based distributed locking patterns |
| Replication | Primary-replica architecture and consistency trade-offs |
| Sentinel | Automatic failover and high availability |
| Cluster | Hash slots, sharding, rebalancing, and scaling |
| AWS ElastiCache | Production deployment patterns on AWS |

---

## Getting Started

### Prerequisites

- Java 21
- Docker Desktop
- Git
- Gradle

### Clone Repository

```bash
git clone https://github.com/DivakarUngatla/redis-production-cookbook.git

cd redis-production-cookbook
```

---

## Running Locally

### Start Redis

```bash
docker compose up -d
```

Verify:

```bash
docker ps
```

### Start Application

```bash
./gradlew bootRun
```

### Verify Redis

```bash
docker exec -it redis-local redis-cli ping
```

Expected output:

```text
PONG
```

---

## AWS ElastiCache

This repository contains examples and deployment patterns for:

- ElastiCache Cluster Mode Disabled
- ElastiCache Cluster Mode Enabled
- Automatic Failover
- Read Replicas
- TLS Encryption
- IAM Authentication
- Monitoring and CloudWatch Integration

---

## Interview Notes

The repository includes architecture notes, production trade-offs, and interview-focused explanations for:

- Redis Data Structures
- Persistence (RDB & AOF)
- Replication
- Sentinel
- Cluster
- Streams
- Search
- Geospatial Indexes
- Redis vs Kafka
- Redis vs Elasticsearch
- AWS ElastiCache

---

## Repository Roadmap

### Foundation

- [ ] String
- [ ] Hash
- [ ] List
- [ ] Set
- [ ] Sorted Set
- [ ] Bitmap
- [ ] HyperLogLog
- [ ] Geo

### Messaging

- [ ] Pub/Sub
- [ ] Streams
- [ ] Consumer Groups

### Search

- [ ] Redis Search
- [ ] BM25 Ranking
- [ ] Distributed Search

### Distributed Systems

- [ ] Replication
- [ ] Sentinel
- [ ] Cluster

### Cloud

- [ ] AWS ElastiCache
- [ ] Observability
- [ ] Production Best Practices
```
:::

This is professional enough for GitHub, and once you start implementing Hash and Sorted Set examples, the README will already look like a serious open-source project rather than a generated Spring Boot starter.