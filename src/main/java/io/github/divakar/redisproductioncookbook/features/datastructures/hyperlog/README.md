# Redis HyperLogLog: Unique Website Visitors

## Introduction

This example demonstrates how to use Redis HyperLogLog to estimate the number of unique website visitors in a Spring Boot application.

HyperLogLog is a probabilistic data structure used for cardinality estimation.

Cardinality means:

```text
How many unique elements exist?
```

Unlike Redis Sets, HyperLogLog does not store individual visitors.

Instead, it stores a compact statistical representation that allows Redis to estimate the number of unique visitors using approximately 12 KB of memory.

---

## What You'll Learn

- How HyperLogLog works
- How to use PFADD
- How to use PFCOUNT
- How to use PFMERGE
- How Redis estimates cardinality
- Memory vs accuracy trade-offs
- Daily and Monthly Unique Visitor analytics

---

## Why Use HyperLogLog?

Suppose a website receives:

```text
100 million visitors
```

and the business asks:

```text
How many unique visitors did we have today?
```

HyperLogLog solves this problem while keeping memory usage almost constant.

Benefits:

- Constant memory usage
- Extremely scalable
- Fast cardinality estimation
- Native merge support
- Ideal for analytics workloads

---

## Why Not a Redis Set?

Redis Sets provide exact cardinality.

Example:

```redis
SADD visitors:2026-06-22 user-123
SADD visitors:2026-06-22 user-456
```

Count:

```redis
SCARD visitors:2026-06-22
```

This approach is perfectly accurate but memory usage grows with the number of visitors.

For:

```text
100 million visitors
```

a Redis Set can consume hundreds of megabytes.

HyperLogLog stores only statistical information required for cardinality estimation.

Memory remains approximately:

```text
12 KB
```

regardless of the number of visitors.

---

## Why Not a Redis Bitmap?

Redis Bitmaps are extremely memory efficient and provide exact counts.

Example:

```redis
SETBIT active-users:2026-06-22 123 1
SETBIT active-users:2026-06-22 456 1
```

Count:

```redis
BITCOUNT active-users:2026-06-22
```

However, Bitmaps still require one bit per user.

For:

```text
100 million users
```

memory usage is approximately:

```text
12.5 MB
```

HyperLogLog goes even further.

Instead of storing activity information for every user, it stores only statistical information needed to estimate cardinality.

Memory usage remains approximately:

```text
12 KB
```

The trade-off is that HyperLogLog provides approximate counts rather than exact counts.

---

## Redis Key Design

Daily visitors:

```text
unique-visitors:2026-06-21
unique-visitors:2026-06-22
unique-visitors:2026-06-23
```

Monthly visitors:

```text
monthly-unique-visitors:2026-06
```

---

## Architecture

```text
                    HTTP Requests
                          |
                          v
            +-------------------------+
            | VisitorController       |
            +-------------------------+
                          |
                          v
            +-------------------------+
            | VisitorRepository       |
            +-------------------------+
                          |
                          v
            +-------------------------+
            | Redis HyperLogLog       |
            | unique-visitors:date    |
            +-------------------------+
```

The controller records visitor activity.

The repository stores visitors in HyperLogLog structures.

Redis estimates cardinality using probabilistic algorithms.

---

## Redis Commands

| Command | Description |
|----------|-------------|
| PFADD | Add visitor |
| PFCOUNT | Estimate unique visitors |
| PFMERGE | Merge HyperLogLogs |

---

## Example Commands

### Record Visitor

```redis
PFADD unique-visitors:2026-06-22 user-123
```

### Record Multiple Visitors

```redis
PFADD unique-visitors:2026-06-22 user-123 user-456 user-789
```

### Count Unique Visitors

```redis
PFCOUNT unique-visitors:2026-06-22
```

### Merge Daily HyperLogLogs

```redis
PFMERGE monthly-unique-visitors:2026-06 \
unique-visitors:2026-06-21 \
unique-visitors:2026-06-22
```

### Count Monthly Visitors

```redis
PFCOUNT monthly-unique-visitors:2026-06
```

---

## Time Complexity

| Command | Complexity |
|----------|------------|
| PFADD | O(1) |
| PFCOUNT | O(1) |
| PFMERGE | O(N) |

Where:

```text
N = Number of HyperLogLogs being merged
```

---

## Memory Comparison

| Structure | Memory |
|------------|----------|
| Set | High |
| Bitmap | ~12.5 MB (100M users) |
| HyperLogLog | ~12 KB |

---

## Set vs Bitmap vs HyperLogLog

| Structure | Exact | Memory |
|------------|--------|---------|
| Set | Yes | High |
| Bitmap | Yes | Medium |
| HyperLogLog | No | ~12 KB |

Trade-off:

```text
Accuracy
     ↑
     |
Set → Bitmap → HyperLogLog
     |
     ↓
Memory Efficiency
```

---

## Daily vs Monthly Unique Visitors

Daily HyperLogLogs:

```text
unique-visitors:2026-06-21
unique-visitors:2026-06-22
unique-visitors:2026-06-23
```

Monthly aggregation:

```redis
PFMERGE monthly-unique-visitors:2026-06 \
unique-visitors:2026-06-21 \
unique-visitors:2026-06-22 \
unique-visitors:2026-06-23
```

Monthly count:

```redis
PFCOUNT monthly-unique-visitors:2026-06
```

---

## Run Example

Start Redis:

```bash
docker compose up -d
```

Start the application:

```bash
./gradlew bootRun
```

---

## REST API Summary

| Method | Endpoint | Description |
|----------|----------|-------------|
| POST | /api/visitors/{date}/{visitorId} | Record visitor |
| GET | /api/visitors/{date}/count | Daily unique visitors |
| POST | /api/visitors/monthly?month=YYYY-MM | Build monthly unique visitor statistics |
| GET | /api/visitors/monthly?month=YYYY-MM | Get monthly unique visitor count |

---

## curl Examples

### Record Visitor

```bash
curl -X POST \
http://localhost:8080/api/visitors/2026-06-22/user-123
```

Response:

```json
{
  "visitorId": "user-123",
  "date": "2026-06-22"
}
```

### Daily Unique Visitors

```bash
curl \
http://localhost:8080/api/visitors/2026-06-22/count
```

Response:

```json
{
  "date": "2026-06-22",
  "uniqueVisitors": 1
}
```

### Build Monthly Unique Visitors (PFMERGE)

```bash
curl -X POST \
"http://localhost:8080/api/visitors/monthly?month=2026-06"
```

Response:

```json
{
  "month": "2026-06",
  "uniqueVisitors": 18542
}
```

Internally:

```redis
PFMERGE monthly-unique-visitors:2026-06 \
unique-visitors:2026-06-21 \
unique-visitors:2026-06-22 \
unique-visitors:2026-06-23
```

### Read Monthly Unique Visitors

```bash
curl \
"http://localhost:8080/api/visitors/monthly?month=2026-06"
```

Response:

```json
{
  "month": "2026-06",
  "uniqueVisitors": 18542
}
```

---

## Inspect Data Directly in Redis

```bash
docker exec redis-local redis-cli \
PFADD unique-visitors:2026-06-22 \
user-123 user-456 user-789
```

```bash
docker exec redis-local redis-cli \
PFCOUNT unique-visitors:2026-06-22
```

Expected:

```text
3
```

---

## Internals

HyperLogLog does not store individual visitors.

Instead:

1. Visitor IDs are hashed.
2. Redis observes patterns in hash values.
3. Redis stores compact statistics.
4. Cardinality is estimated mathematically.

Example:

```text
user-123
    |
    v
Hash

000000001010101...
```

Redis stores:

```text
16384 registers
```

which consume approximately:

```text
12 KB
```

regardless of the number of visitors.

---

## How HyperLogLog Works

Most hashes look like:

```text
101001...
011010...
001001...
```

Rare hashes look like:

```text
000000000001...
```

Very rare hashes look like:

```text
000000000000000001...
```

The more leading zeros Redis observes, the larger the estimated cardinality.

HyperLogLog uses this statistical property to estimate unique counts.

---

## Accuracy

Redis HyperLogLog provides:

```text
~0.81% standard error
```

Example:

Actual visitors:

```text
1,000,000
```

Estimated visitors:

```text
991,800
```

or

```text
1,007,500
```

---

## Scaling Strategies

### Daily HyperLogLogs

Create one HyperLogLog per day.

### Monthly Aggregation

Use:

```redis
PFMERGE
```

to build monthly statistics.

### Retention

```redis
EXPIRE unique-visitors:2026-06-21 7776000
```

Example:

```text
90 days retention
```

---

## Production Considerations

### HyperLogLog Is Approximate

Counts are estimates.

### No Membership Queries

HyperLogLog can answer:

```text
How many unique visitors?
```

but cannot answer:

```text
Did user-123 visit?
```

### Great for Analytics

Ideal for:

- Website visitors
- Ad impressions
- Unique users
- API consumers
- Marketing analytics

---

## Complete Example

Day 1:

```redis
PFADD unique-visitors:2026-06-21 \
user-1 user-2 user-3
```

Day 2:

```redis
PFADD unique-visitors:2026-06-22 \
user-2 user-4
```

Monthly aggregation:

```redis
PFMERGE monthly-unique-visitors:2026-06 \
unique-visitors:2026-06-21 \
unique-visitors:2026-06-22
```

Count:

```redis
PFCOUNT monthly-unique-visitors:2026-06
```

Result:

```text
4
```

Unique visitors:

```text
user-1
user-2
user-3
user-4
```

Notice that user-2 appears on both days but is counted only once.

---

## Key Takeaways

- HyperLogLog estimates cardinality
- Memory remains approximately 12 KB
- PFADD records visitors
- PFCOUNT estimates unique visitors
- PFMERGE combines HyperLogLogs
- Accuracy is approximately 99.2%
- Ideal for large-scale analytics


## Interview Notes

### What problem does HyperLogLog solve?

HyperLogLog provides memory-efficient cardinality estimation.

### How much memory does Redis HyperLogLog use?

Approximately:

```text
12 KB
```

### Is HyperLogLog exact?

No. It is probabilistic.

### What is the error rate?

Approximately:

```text
0.81%
```

### Can HyperLogLog determine whether a visitor exists?

No. It only estimates cardinality.

### When would you choose HyperLogLog instead of a Bitmap?

When memory efficiency is more important than exact counts.

### When would you choose HyperLogLog instead of a Set?

When cardinality is more important than membership and memory usage must remain constant.

## Interview Notes

### What problem does HyperLogLog solve?

HyperLogLog provides memory-efficient cardinality estimation.

It answers:

```text
How many unique elements exist?
```

without storing every element.

---

### How much memory does Redis HyperLogLog use?

Approximately:

```text
12 KB
```

regardless of whether the structure contains thousands or billions of unique elements.

---

### Is HyperLogLog exact?

No.

HyperLogLog is a probabilistic data structure.

Redis provides approximately:

```text
0.81% standard error
```

for cardinality estimation.

---

### How does HyperLogLog work internally?

Elements are hashed and distributed across 16,384 registers.

Redis tracks the maximum number of leading zeros observed in each register and uses statistical estimation to calculate cardinality.

---

### What commands are used with HyperLogLog?

```redis
PFADD
PFCOUNT
PFMERGE
```

---

### Can HyperLogLog determine whether a visitor exists?

No.

HyperLogLog can estimate:

```text
How many?
```

but cannot answer:

```text
Does user-123 exist?
```

because individual elements are not stored.

---

### When would you choose HyperLogLog instead of a Bitmap?

Choose HyperLogLog when:

- Approximate counts are acceptable
- Memory efficiency is critical
- Membership queries are unnecessary

Choose Bitmap when:

- Exact counts are required
- User IDs can be mapped to numeric offsets

---

### When would you choose HyperLogLog instead of a Set?

Choose HyperLogLog when cardinality is more important than membership.

A Set stores every element and supports membership checks, but memory grows with dataset size.

HyperLogLog sacrifices exactness in exchange for constant memory usage.