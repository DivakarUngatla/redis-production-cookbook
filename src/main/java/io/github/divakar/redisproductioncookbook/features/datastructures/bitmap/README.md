# Redis Bitmaps: Daily Active Users (DAU)

This example demonstrates how to use Redis Bitmaps to track Daily Active Users (DAU) and Monthly Active Users (MAU) in a Spring Boot application.

Bitmaps provide an extremely memory-efficient way to track boolean states for large populations of users.

Example:

```text
User ID -> Bit Position

User 123 -> Bit 123
User 456 -> Bit 456
User 789 -> Bit 789
```

If a user is active:

```redis
SETBIT active-users:2026-06-22 123 1
```

Redis sets the bit at position 123 to 1.

---

## Why Use Redis Bitmaps?

Bitmaps are ideal when:

- Tracking user activity
- Tracking feature adoption
- Tracking login events
- Tracking attendance
- Counting active users
- Analytics workloads

Benefits:

- Extremely memory efficient
- Constant-time updates
- Fast counting
- Supports very large user populations
- Exact counts (unlike approximate algorithms)

---

## How Bitmaps Work

Imagine a system with ten users:

```text
User IDs

0 1 2 3 4 5 6 7 8 9
```

Initially:

```text
0 0 0 0 0 0 0 0 0 0
```

Users 1, 4, and 7 become active:

```text
0 1 0 0 1 0 0 1 0 0
```

Each bit position corresponds to a user ID:

```text
Bit Position -> User ID
Bit Value    -> Active or Inactive

0 = inactive
1 = active
```

Redis stores bits instead of user identifiers, dramatically reducing memory usage.

---

## Why Not a Relational Database?

A relational database can store activity events:

```text
activity_log

user_id
activity_date
```

However:

```text
100 million users
x
365 days
```

creates a massive dataset.

Redis Bitmaps store only one bit per user.

Example:

```text
100 million users
=
100 million bits
=
12.5 MB
```

This makes Bitmaps extremely attractive for analytics workloads.

Redis is not always a replacement for a relational database.

Many systems use:

```text
Activity Events
        |
        v
 Relational Database

Analytics Counters
        |
        v
   Redis Bitmap
```

---

## Redis Key Design

Daily active users:

```text
active-users:2026-06-21
active-users:2026-06-22
active-users:2026-06-23
```

Monthly aggregates:

```text
monthly-active-users:2026-06
```

Each bit position represents a user.

Example:

```text
Bit Position    User ID

123             123
456             456
789             789
```

---

## Architecture

```text
                    HTTP Requests
                          |
                          v
             +------------------------+
             | UserActivityController |
             +------------------------+
                          |
                          v
             +------------------------+
             | UserActivityRepository |
             +------------------------+
                          |
                          v
             +------------------------+
             | Redis Bitmap           |
             | active-users:date      |
             +------------------------+
```

The controller accepts activity events.

The repository maps user IDs to bit positions and executes Bitmap operations.

Redis stores activity using individual bits.

---

## Redis Commands

| Command | Description |
|----------|-------------|
| SETBIT | Mark user active |
| SETBIT key offset 0 | Mark user inactive |
| GETBIT | Check activity |
| BITCOUNT | Count active users |
| BITOP | Combine bitmaps |

---

## Example Commands

### Mark User Active

```redis
SETBIT active-users:2026-06-22 123 1
```

### Check User Activity

```redis
GETBIT active-users:2026-06-22 123
```

Result:

```text
1
```

### Count Daily Active Users

```redis
BITCOUNT active-users:2026-06-22
```

### Create Monthly Active Users

```redis
BITOP OR monthly-active-users:2026-06 \
active-users:2026-06-21 \
active-users:2026-06-22
```

---

## Time Complexity

| Command | Complexity |
|----------|------------|
| SETBIT | O(1) |
| GETBIT | O(1) |
| BITCOUNT | O(N) |
| BITOP | O(N) |

Where:

```text
N = Bitmap size in bytes
```

---

## Memory Comparison

Suppose we need to track 100 million users.

### Redis Set

```text
SADD active-users 123
SADD active-users 456
...
```

Stores every user identifier.

Memory usage can easily reach hundreds of megabytes.

### Redis Bitmap

```text
SETBIT active-users 123 1
SETBIT active-users 456 1
```

Stores one bit per user.

Memory usage:

```text
100 million bits

÷ 8

=

12.5 MB
```

This is why Bitmaps are famous in large-scale analytics systems.

---

## DAU vs MAU

A common analytics requirement is:

- Daily Active Users (DAU)
- Monthly Active Users (MAU)

### Daily Active Users

Create one bitmap per day:

```text
active-users:2026-06-21
active-users:2026-06-22
active-users:2026-06-23
```

Count active users:

```redis
BITCOUNT active-users:2026-06-22
```

### Monthly Active Users

Combine daily bitmaps:

```redis
BITOP OR monthly-active-users:2026-06 \
active-users:2026-06-21 \
active-users:2026-06-22 \
active-users:2026-06-23
```

Count unique users:

```redis
BITCOUNT monthly-active-users:2026-06
```

This is one of the most common Bitmap use cases in production.

---

## Common Use Cases

Redis Bitmaps are commonly used for:

- Daily Active Users (DAU)
- Monthly Active Users (MAU)
- Login Tracking
- Attendance Systems
- Feature Adoption
- Email Open Tracking
- Notification Delivery Tracking
- Marketing Analytics

---

## Bitmap vs Set

| Feature | Set | Bitmap |
|----------|----------|----------|
| Exact Membership | Yes | Yes |
| Memory Efficient | No | Yes |
| Arbitrary Strings | Yes | No |
| Numeric IDs Required | No | Yes |
| Active User Analytics | Good | Excellent |

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
| POST | /api/user-activity/{date}/users/{userId} | Mark user active |
| DELETE | /api/user-activity/{date}/users/{userId} | Mark user inactive |
| GET | /api/user-activity/{date}/users/{userId} | Check user activity |
| GET | /api/user-activity/{date}/count | Count daily active users |
| POST | /api/user-activity/monthly?month=YYYY-MM | Build monthly bitmap and return MAU |
| GET | /api/user-activity/monthly?month=YYYY-MM | Read previously calculated MAU |

---

## curl Examples

### Mark User Active

```bash
curl -i -X POST \
http://localhost:8080/api/user-activity/2026-06-22/users/123
```

Expected:

```text
HTTP/1.1 201 Created
```

Response:

```json
{
  "userId": 123,
  "date": "2026-06-22",
  "active": true
}
```

### Mark User Inactive

```bash
curl -X DELETE \
http://localhost:8080/api/user-activity/2026-06-22/users/123
```

Response:

```json
{
  "userId": 123,
  "date": "2026-06-22",
  "active": false
}
```

### Check User Activity

```bash
curl \
http://localhost:8080/api/user-activity/2026-06-22/users/123
```

Response:

```json
{
  "userId": 123,
  "date": "2026-06-22",
  "active": true
}
```

### Count Daily Active Users

```bash
curl \
http://localhost:8080/api/user-activity/2026-06-22/count
```

Response:

```json
{
  "date": "2026-06-22",
  "dailyActiveUsers": 1
}
```

### Calculate Monthly Active Users

```bash
curl -X POST \
"http://localhost:8080/api/user-activity/monthly?month=2026-06"
```

Response:

```json
{
  "month": "2026-06",
  "monthlyActiveUsers": 18542
}
```

### Read Previously Calculated Monthly Active Users

```bash
curl \
"http://localhost:8080/api/user-activity/monthly?month=2026-06"
```

Response:

```json
{
  "month": "2026-06",
  "monthlyActiveUsers": 18542
}
```

---

## Inspect Data Directly in Redis

Check whether user 123 was active:

```bash
docker exec redis-local redis-cli \
GETBIT active-users:2026-06-22 123
```

Expected:

```text
1
```

Count active users:

```bash
docker exec redis-local redis-cli \
BITCOUNT active-users:2026-06-22
```

Expected:

```text
1
```

---

## Internals

One surprising fact about Redis Bitmaps is that Bitmap is not a separate Redis data structure.

Internally, Redis stores Bitmaps using Redis Strings.

For example:

```redis
SETBIT active-users:2026-06-22 123 1
```

actually modifies a bit inside a Redis String.

This means:

- Bitmaps inherit Redis String scalability characteristics
- Bitmaps can grow automatically as higher offsets are written
- Bitmap operations are implemented as specialized String operations

Conceptually:

```text
Redis String
      |
      v
010010001001000100100010
      |
      v
Bitmap View
```

This is why Bitmaps are extremely memory efficient.

A String containing:

```text
100 million bits
```

requires approximately:

```text
12.5 MB
```

of memory.

### How Redis Grows Bitmaps

Suppose the first operation is:

```redis
SETBIT active-users:2026-06-22 123 1
```

Redis automatically expands the underlying String to accommodate bit position 123.

Later:

```redis
SETBIT active-users:2026-06-22 1000000 1
```

causes Redis to expand again.

Applications do not need to preallocate storage.

### Important Limitation

Bitmaps work best when user identifiers are relatively dense.

Good:

```text
1
2
3
4
5
```

Potentially wasteful:

```text
1
1000000000
```

because Redis must allocate space up to the highest bit offset.

This is one of the most important production considerations when designing Bitmap-based systems.

---

## Scaling Strategies

### Daily Keys

```text
active-users:2026-06-21
active-users:2026-06-22
active-users:2026-06-23
```

One bitmap per day.

### Monthly Aggregation

```redis
BITOP OR
```

combines multiple daily bitmaps.

### Retention Policies

Historical data can be expired:

```redis
EXPIRE active-users:2026-06-21 7776000
```

Example:

```text
90 days retention
```

### Analytics Pipelines

Many organizations export Bitmap-derived metrics into:

- Data Warehouses
- Data Lakes
- BI Systems

while Redis serves real-time analytics needs.

---

## Production Considerations

### Numeric User IDs

Bitmaps require numeric offsets.

User identifiers often need mapping:

```text
user-123
      |
      v
123
```

### Sparse User IDs

If user IDs are:

```text
1
1000000000
```

memory usage may become inefficient.

Bitmap density matters.

### Expiration

Apply TTLs to historical data.

### Rebuilding Monthly Aggregations

Monthly bitmaps are derived data.

If required, they can be rebuilt at any time from daily bitmaps using:

```redis
BITOP OR
```

Many production systems periodically rebuild aggregate bitmaps as part of scheduled analytics jobs.

### Exact vs Approximate Counting

Bitmaps provide exact counts.

If approximate counts are acceptable, HyperLogLog may reduce memory usage even further.

---

## Complete DAU and MAU Example

Day 1:

```redis
SETBIT active-users:2026-06-21 123 1
SETBIT active-users:2026-06-21 456 1
```

Day 2:

```redis
SETBIT active-users:2026-06-22 456 1
SETBIT active-users:2026-06-22 789 1
```

Create a monthly bitmap:

```redis
BITOP OR monthly-active-users:2026-06 \
active-users:2026-06-21 \
active-users:2026-06-22
```

Count monthly active users:

```redis
BITCOUNT monthly-active-users:2026-06
```

Result:

```text
3
```

Users:

```text
123
456
789
```

Notice that user 456 was active on both days but is counted only once.

This is one of the most important Bitmap patterns in analytics systems.

---

## Interview Notes

### Why use Bitmaps?

To track large populations using minimal memory.

### Why are Bitmaps memory efficient?

Each user consumes only one bit.

### What is the complexity of SETBIT?

```text
O(1)
```

### Bitmap vs Set?

Bitmap:
- Exact counts
- Minimal memory
- Numeric IDs required

Set:
- More flexible
- Higher memory usage

### Bitmap vs HyperLogLog?

Bitmap:
- Exact counts
- More memory

HyperLogLog:
- Approximate counts
- ~12 KB memory

### When would you choose Bitmaps?

When exact active-user counts are required and user IDs can be mapped to numeric offsets.