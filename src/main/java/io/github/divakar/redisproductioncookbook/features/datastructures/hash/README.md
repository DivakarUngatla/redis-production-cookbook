# Redis Hashes: User Profile Repository

This example demonstrates how to use Redis Hashes to store and manage user profiles in a Spring Boot application.

Each profile is stored as a Redis Hash using a key such as:

```text
user:profile:{user-123}
```

The hash contains the following fields:

```text
id
name
email
age
```

---

## Why Use Redis Hashes?

Redis Hashes are ideal for representing small objects and entities.

Benefits include:

- No need to serialize and deserialize an entire JSON document
- Individual fields can be updated independently
- Memory efficient for small objects
- Fast field-level access using Redis hash commands
- Easy to model domain entities such as users, products, sessions, and preferences

Example:

```text
user:profile:{user-123}

id=user-123
name=Divakar
email=divakar@example.com
age=30
```

---

## Why Not a Relational Database?

A relational database is a natural choice for storing user profiles and supports:

- Indexes
- Transactions
- Joins
- Analytics
- Durable storage

Redis Hashes become attractive when:

- Extremely fast profile lookups are required
- Frequently accessed profile attributes exist
- Low latency matters
- Partial field updates are common
- Profiles are used as cache entries

Redis is not always a replacement for a relational database.

Many production systems use the following architecture:

```text
Primary Database
       |
       v
    Redis Hash
       |
       v
 Application
```

The relational database remains the system of record while Redis acts as a high-speed cache.

---

## Redis Key Design

User profiles are stored using:

```text
user:profile:{user-123}
```

The value inside braces:

```text
{user-123}
```

is a Redis Hash Tag.

Hash tags ensure related keys are mapped to the same Redis Cluster hash slot.

Example:

```text
user:profile:{user-123}
user:settings:{user-123}
user:orders:{user-123}
```

All three keys will be stored on the same cluster shard.

---

## Architecture

```text
                  HTTP Requests
                       |
                       v
          +------------------------+
          | UserProfileController  |
          +------------------------+
                       |
                       v
          +------------------------+
          | UserProfileRepository  |
          +------------------------+
                       |
                       v
          +------------------------+
          | Redis Hash             |
          | user:profile:{userId}  |
          |                        |
          | id=user-123            |
          | name=Divakar           |
          | email=divakar@xyz.com  |
          | age=30                 |
          +------------------------+
```

The controller handles HTTP requests and validation.

The repository maps the domain model to Redis hash fields and performs Redis operations.

Each profile attribute is stored independently, allowing field-level updates without rewriting the entire object.

---

## Redis Commands

This example demonstrates the following Redis commands:

| Command | Description |
|----------|-------------|
| HSET | Create or update fields in a hash |
| HGET | Read a single field |
| HGETALL | Read the entire hash |
| HDEL | Delete fields from a hash |
| EXPIRE | Optional expiration |

---

## Example Commands

### Create Profile

```redis
HSET user:profile:{user-123} \
id user-123 \
name Divakar \
email divakar@example.com \
age 30
```

### Read Entire Profile

```redis
HGETALL user:profile:{user-123}
```

### Read Single Field

```redis
HGET user:profile:{user-123} email
```

### Update Age

```redis
HSET user:profile:{user-123} age 31
```

### Delete Field

```redis
HDEL user:profile:{user-123} age
```

---

## Time Complexity

| Command | Complexity |
|----------|------------|
| HSET | O(1) |
| HGET | O(1) |
| HDEL | O(1) |
| HGETALL | O(N) |
| EXPIRE | O(1) |

Where:

```text
N = Number of fields in the hash
```

`HGETALL` should be used carefully on very large hashes because Redis must return every field.

---

## Common Use Cases

Redis Hashes are commonly used for:

- User Profiles
- Product Metadata
- Customer Preferences
- Shopping Cart Attributes
- Session Attributes
- Feature Flags
- Configuration Data
- User Settings
- Device Metadata

---

## Redis Hash Internals

Redis Hashes use different internal encodings depending on their size.

For small hashes, Redis uses a compact representation called a Listpack.

For larger hashes, Redis automatically converts the structure into a Hashtable.

```text
Small Hash
    |
    v
 Listpack

Large Hash
    |
    v
 Hashtable
```

Benefits:

- Reduced memory consumption
- Efficient field-level access
- Fast updates
- Automatic optimization

Redis performs these conversions automatically.

---

## Hash vs JSON String

A common design question is whether to store objects as:

```text
String -> JSON Document
```

or

```text
Redis Hash
```

### JSON String

```text
user:profile:{user-123}

{
  "id":"user-123",
  "name":"Divakar",
  "email":"divakar@example.com",
  "age":30
}
```

Updating a single field requires:

1. Read the document
2. Deserialize
3. Modify
4. Serialize
5. Write the document back

### Redis Hash

Update only the field:

```text
HSET user:profile:{user-123} age 31
```

Advantages:

#### Hashes

- Field-level updates
- Smaller writes
- Lower serialization overhead
- Individual field access
- Memory efficient

#### JSON

- Nested structures
- Easier document modeling
- Better for complex objects

For nested documents, RedisJSON may be a better choice.

---

## Cluster Considerations

The entire hash resides on a single shard.

Example:

```text
user:profile:{user-123}
```

All fields for that profile are stored together.

Using the same hash tag:

```text
user:profile:{user-123}
user:settings:{user-123}
user:orders:{user-123}
```

ensures all related keys map to the same hash slot.

Benefits:

- Multi-key operations become possible
- Reduced cross-shard traffic
- Better data locality

---

## Scaling Strategies

Redis Hashes can support millions of profiles when memory is sized correctly.

### Cache-Aside Pattern

```text
Application
      |
      v
Redis Hash
      |
 Cache Miss
      |
      v
Database
```

Profiles are loaded into Redis on demand.

### Hot Profile Management

Some users receive significantly more traffic:

- Administrators
- Celebrity accounts
- Shared accounts

Monitor:

- Hot keys
- Command latency
- Memory usage

### Read Scaling

Read replicas can offload reads.

Trade-off:

```text
More Read Throughput
       |
       v
Potentially Stale Reads
```

Applications requiring strong consistency should continue reading from the primary.

### Profile Hydration

Many systems store:

```text
Complete Profile
        |
        v
 Relational Database

Frequently Accessed Fields
        |
        v
     Redis Hash
```

---

## Run the Example

### Start Redis

```bash
docker compose up -d
```

### Start Application

```bash
./gradlew bootRun
```

---

## Create a User Profile

```bash
curl -i -X POST http://localhost:8080/api/user-profiles \
  -H "Content-Type: application/json" \
  -d '{
        "id":"user-123",
        "name":"Divakar",
        "email":"divakar@example.com",
        "age":30
      }'
```

Expected:

```text
HTTP/1.1 201 Created
```

---

## Read a User Profile

```bash
curl http://localhost:8080/api/user-profiles/user-123
```

Response:

```json
{
  "id": "user-123",
  "name": "Divakar",
  "email": "divakar@example.com",
  "age": 30
}
```

---

## Delete a User Profile

```bash
curl -i -X DELETE \
http://localhost:8080/api/user-profiles/user-123
```

Expected:

```text
HTTP/1.1 204 No Content
```

---

## Inspect Data Directly in Redis

```bash
docker exec -it redis-local redis-cli \
HGETALL 'user:profile:{user-123}'
```

Example output:

```text
1) "id"
2) "user-123"
3) "name"
4) "Divakar"
5) "email"
6) "divakar@example.com"
7) "age"
8) "30"
```

---

## Production Considerations

### Cache Invalidation

Profile updates must invalidate or refresh cached copies.

### TTL Strategy

Profiles may:

- Never expire
- Expire after inactivity
- Be refreshed periodically

Choose based on access patterns.

### Cache Stampede Protection

Protect frequently accessed profiles using:

- Request coalescing
- Refresh-ahead
- Distributed locks

### Serialization Versioning

Profile schemas evolve.

Applications should tolerate missing or additional fields.

### PII Handling

User profiles often contain sensitive information.

Consider:

- Encryption
- Access controls
- Data retention requirements

### Durability

Redis can be used as:

- Cache
- Primary Store
- Hybrid Store

Each requires different persistence, backup, and replication strategies.

---

## Memory Considerations

Although Redis Hashes are memory efficient, every field introduces overhead.

Consider:

- Number of fields
- Average field size
- Key overhead
- Replication overhead
- Persistence overhead

Large numbers of tiny hashes can still consume significant memory.

Redis mitigates this through Listpack encoding.

Measure memory usage using production-like datasets.

---

## Interview Notes

### Why use a Redis Hash instead of a String?

Hashes support field-level access and updates.

Instead of rewriting an entire JSON document:

```text
SET user:profile:{user-123} {...}
```

Redis can update a single field:

```text
HSET user:profile:{user-123} age 31
```

---

### What is the complexity of HSET?

```text
O(1)
```

Average case.

---

### What is the complexity of HGET?

```text
O(1)
```

Average case.

---

### What is the complexity of HGETALL?

```text
O(N)
```

Where:

```text
N = number of fields in the hash
```

---

### When does Redis switch from Listpack to Hashtable?

Redis stores small hashes using Listpack encoding.

When configurable thresholds are exceeded, Redis automatically converts the structure into a Hashtable.

---

### Hash vs RedisJSON

Hashes:

- Memory efficient
- Simple
- Field-level updates

RedisJSON:

- Nested documents
- JSONPath queries
- Rich document operations

---

### Hash vs Relational Database

Relational databases provide:

- Transactions
- Joins
- Analytics
- Durable storage

Redis Hashes provide:

- Low latency
- In-memory access
- Fast caching

Most production systems use both.

---

### How do Hashes behave in Redis Cluster?

Hashes themselves are not distributed.

The entire hash lives on one shard.

Example:

```text
user:profile:{user-123}
```

Related keys using the same hash tag:

```text
user:profile:{user-123}
user:settings:{user-123}
user:orders:{user-123}
```

are assigned to the same slot and shard.