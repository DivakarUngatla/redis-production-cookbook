# Redis Sets: User Roles and Permissions

This example demonstrates how to use Redis Sets to manage user roles and permissions in a Spring Boot application.

Each user has a Redis Set containing assigned roles.

Example key:

```text
user:roles:{user-123}
```

Example members:

```text
ADMIN
AUTHOR
PREMIUM
```

---

## Why Use Redis Sets?

Redis Sets are ideal when:

- Duplicate values must be prevented
- Fast membership checks are required
- Set operations are useful
- Order does not matter

Benefits:

- Automatic duplicate prevention
- O(1) membership checks
- Efficient set algebra
- Simple authorization lookups

Example:

```text
user:roles:{user-123}

ADMIN
AUTHOR
PREMIUM
```

Redis guarantees uniqueness automatically.

---

## Why Not a Relational Database?

A relational database is a natural choice for role management and authorization.

Typical schema:

```text
users
roles
user_roles
```

Redis Sets become attractive when:

- Authorization checks happen frequently
- Low latency matters
- Roles are cached in memory
- Membership lookups dominate workloads

Redis is not always a replacement for a relational database.

Many systems use:

```text
Database
    |
    v
Redis Set
    |
    v
Application
```

The database remains the source of truth while Redis accelerates authorization decisions.

---

## Redis Key Design

User roles are stored using:

```text
user:roles:{user-123}
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
user:roles:{user-123}
user:permissions:{user-123}
```

All keys will be stored on the same cluster shard.

---

## Architecture

```text
                 HTTP Requests
                        |
                        v
           +------------------------+
           | UserRolesController    |
           +------------------------+
                        |
                        v
           +------------------------+
           | UserRolesRepository    |
           +------------------------+
                        |
                        v
           +------------------------+
           | Redis Set              |
           | user:roles:{userId}    |
           |                        |
           | ADMIN                  |
           | AUTHOR                 |
           | PREMIUM                |
           +------------------------+
```

The controller handles API requests.

The repository maps user-role relationships to Redis Sets.

Redis provides constant-time membership checks for authorization decisions.

---

## Redis Commands

This example demonstrates:

| Command | Description |
|----------|-------------|
| SADD | Add role |
| SREM | Remove role |
| SISMEMBER | Check membership |
| SMEMBERS | Get all roles |
| SCARD | Count roles |
| DEL | Delete role set |

---

## Example Commands

### Add Roles

```redis
SADD user:roles:{user-123} ADMIN
SADD user:roles:{user-123} AUTHOR
SADD user:roles:{user-123} PREMIUM
```

### Check Membership

```redis
SISMEMBER user:roles:{user-123} ADMIN
```

Returns:

```text
1
```

### Get All Roles

```redis
SMEMBERS user:roles:{user-123}
```

### Count Roles

```redis
SCARD user:roles:{user-123}
```

### Remove Role

```redis
SREM user:roles:{user-123} PREMIUM
```

---

## Time Complexity

| Command | Complexity |
|----------|------------|
| SADD | O(1) |
| SREM | O(1) |
| SISMEMBER | O(1) |
| SCARD | O(1) |
| SMEMBERS | O(N) |

Where:

```text
N = Number of members in the set
```

---

## Common Use Cases

Redis Sets are commonly used for:

- User Roles
- Permissions
- Feature Access
- User Groups
- Followers
- Tags
- Product Categories
- Shopping Cart Items
- Online Users

---

## Redis Set Internals

Redis Sets use different internal representations depending on the stored values.

For small integer-only sets:

```text
IntSet
```

For general values:

```text
Hash Table
```

Redis automatically switches between encodings.

Benefits:

- Memory efficiency
- Fast membership checks
- Efficient uniqueness enforcement

---

## Set Operations

One of the most powerful features of Redis Sets is native set algebra.

### Union

```redis
SUNION admins authors
```

Returns all unique members.

### Intersection

```redis
SINTER admins premium_users
```

Returns users present in both sets.

### Difference

```redis
SDIFF all_users banned_users
```

Returns users present in the first set but not the second.

### Complete Set Algebra Example

Create two sets:

```redis
SADD user:roles:{user-123} ADMIN AUTHOR PREMIUM
SADD user:roles:{user-456} AUTHOR REVIEWER
```

Visual representation:

```text
user-123 = {ADMIN, AUTHOR, PREMIUM}

user-456 = {AUTHOR, REVIEWER}
```

#### Union (SUNION)

Returns all unique members from both sets.

```redis
SUNION user:roles:{user-123} user:roles:{user-456}
```

Result:

```text
ADMIN
AUTHOR
PREMIUM
REVIEWER
```

#### Intersection (SINTER)

Returns members present in both sets.

```redis
SINTER user:roles:{user-123} user:roles:{user-456}
```

Result:

```text
AUTHOR
```

#### Difference (SDIFF)

Returns members present in the first set but not the second.

```redis
SDIFF user:roles:{user-123} user:roles:{user-456}
```

Result:

```text
ADMIN
PREMIUM
```

Visualizing the difference:

```text
user-123 = {ADMIN, AUTHOR, PREMIUM}
user-456 = {AUTHOR, REVIEWER}

Common Roles
    ↓
{AUTHOR}

Only user-123
    ↓
{ADMIN, PREMIUM}

Combined Roles
    ↓
{ADMIN, AUTHOR, PREMIUM, REVIEWER}
```

#### Why Set Algebra Matters

Set algebra enables powerful authorization and access-control queries:

- Common permissions between users
- Missing permissions
- Combined access rights
- Group membership analysis
- Feature entitlement checks

These operations execute directly inside Redis and avoid transferring large datasets to the application.

---

## REST API Examples

The repository also exposes Set algebra operations through REST APIs.

### Setup Sample Users

Assign roles to two users:

```bash
curl -X POST http://localhost:8080/api/user-roles/user-123 \
-H "Content-Type: application/json" \
-d '["ADMIN","AUTHOR","PREMIUM"]'
```

```bash
curl -X POST http://localhost:8080/api/user-roles/user-456 \
-H "Content-Type: application/json" \
-d '["AUTHOR","REVIEWER"]'
```

### Union (SUNION)

Returns all unique roles across both users.

```bash
curl "http://localhost:8080/api/user-roles/union?userId1=user-123&userId2=user-456"
```

Response:

```json
[
  "ADMIN",
  "AUTHOR",
  "PREMIUM",
  "REVIEWER"
]
```

### Intersection (SINTER)

Returns roles shared by both users.

```bash
curl "http://localhost:8080/api/user-roles/intersection?userId1=user-123&userId2=user-456"
```

Response:

```json
[
  "AUTHOR"
]
```

### Difference (SDIFF)

Returns roles present in user-123 but not user-456.

```bash
curl "http://localhost:8080/api/user-roles/difference?userId1=user-123&userId2=user-456"
```

Response:

```json
[
  "ADMIN",
  "PREMIUM"
]
```

---

## Cluster Considerations

A Redis Set belongs to a single shard.

Example:

```text
user:roles:{user-123}
```

All roles for that user are stored together.

Related keys:

```text
user:profile:{user-123}
user:roles:{user-123}
user:permissions:{user-123}
```

share the same hash tag and therefore the same slot.

This becomes important when multi-key operations are required.

---

## Scaling Strategies

### Authorization Cache

A common pattern:

```text
Database
      |
      v
Redis Roles Cache
      |
      v
Application
```

Applications can perform authorization checks without querying the database.

### Hot Users

Some users may receive significantly more traffic:

- Administrators
- Service Accounts
- Shared Accounts

Monitor:

- Hot keys
- Command latency
- Memory usage

### Read Scaling

Role lookups are excellent candidates for read replicas.

Trade-off:

```text
More Throughput
       |
       v
Potentially Stale Authorization Data
```

Applications requiring immediate consistency should read from the primary.

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

## Add Roles

```bash
curl -X POST http://localhost:8080/api/user-roles/user-123 \
-H "Content-Type: application/json" \
-d '["ADMIN","AUTHOR"]'
```
Response:

```json
{
  "userId": "user-123",
  "roles": [
    "ADMIN",
    "AUTHOR"
  ],
  "roleCount": 2
}
```

---

## Check Membership

```bash
curl http://localhost:8080/api/user-roles/user-123/roles/ADMIN
```

Response:

```json
{
  "userId":"user-123",
  "role":"ADMIN",
  "assigned":true
}
```

---

## Get Roles

```bash
curl http://localhost:8080/api/user-roles/user-123
```

Response:

```json
[
  "ADMIN",
  "AUTHOR"
]
```

---

## Production Considerations

### Role Changes

Role updates should invalidate cached authorization decisions.

### Avoid Large Sets

Very large sets can increase memory consumption and network transfer costs.

### Consistency

Authorization systems often require stronger consistency guarantees than caches.

Consider:

- Primary reads
- Event-driven updates
- Short cache lifetimes

### Auditing

Redis should not be the only source of authorization data.

Maintain audit records in durable storage.

---

## Interview Notes

### Why use a Redis Set?

Sets provide:

- Uniqueness
- Fast membership checks
- Set algebra operations

---

### What is the complexity of SISMEMBER?

```text
O(1)
```

Average case.

---

### How are duplicates handled?

Redis Sets automatically ignore duplicates.

Example:

```redis
SADD user:roles:{user-123} ADMIN
SADD user:roles:{user-123} ADMIN
```

The set still contains only one member.

---

### When would you use a Set instead of a List?

Use a Set when:

- Order does not matter
- Uniqueness matters
- Membership checks are common

Use a List when:

- Order matters
- History matters
- Queue semantics are required

---

### Set vs Sorted Set

Set:

```text
Unique Members
```

Sorted Set:

```text
Unique Members + Score + Ordering
```

---

### How do Sets behave in Redis Cluster?

The entire set is stored on a single shard.

Keys sharing the same hash tag are colocated on the same slot.

Example:

```text
user:profile:{user-123}
user:roles:{user-123}
user:permissions:{user-123}
```

All reside on the same shard.