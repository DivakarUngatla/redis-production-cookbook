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

- No need to serialize and deserialize an entire JSON document.
- Individual fields can be updated independently.
- Memory efficient for small objects.
- Fast field-level access using Redis hash commands.
- Easy to model domain entities such as users, products, sessions, and preferences.

Example:

```text
user:profile:{user-123}

id=user-123
name=Divakar
email=divakar@example.com
age=30
```

---

## Redis Commands

This example demonstrates the following Redis commands:

| Command | Description |
|----------|-------------|
| HSET | Create or update fields in a hash |
| HGET | Read a single field |
| HGETALL | Read the entire hash |
| HDEL | Delete fields from a hash |
| EXPIRE | Optional expiration for temporary profiles |

---

## Time Complexity

| Command | Complexity |
|----------|------------|
| HSET | O(1) |
| HGET | O(1) |
| HGETALL | O(N) |
| HDEL | O(1) |
| EXPIRE | O(1) |

Where:

```text
N = Number of fields in the hash
```

---

## Common Use Cases

Redis Hashes are commonly used for:

- User Profiles
- Product Metadata
- Customer Preferences
- Session Attributes
- Feature Flags
- Shopping Cart Attributes
- Configuration Data

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

1. Reading the entire JSON document.
2. Deserializing it.
3. Updating the field.
4. Serializing it again.
5. Writing the entire document back.

### Redis Hash

Only the modified field needs to be updated:

```text
HSET user:profile:{user-123} age 31
```

Advantages:

- Smaller writes
- Faster updates
- Field-level access
- Better memory efficiency for small objects

Redis Hashes are generally preferred when the application frequently updates individual fields.

---

## Redis Cluster Note

This example uses:

```text
user:profile:{user-123}
```

The value inside braces:

```text
{user-123}
```

is called a Redis Hash Tag.

Hash Tags ensure related keys are mapped to the same Redis Cluster hash slot.

Examples:

```text
user:profile:{user-123}
user:settings:{user-123}
user:orders:{user-123}
```

All three keys will be stored on the same cluster shard.

This becomes important when performing multi-key operations in a Redis Cluster.

---

## Run the Example

### Start Redis

```bash
docker compose up -d
```

### Start the Application

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

Expected Response:

```text
HTTP/1.1 201 Created
```

---

## Read a User Profile

```bash
curl http://localhost:8080/api/user-profiles/user-123
```

Example Response:

```json
{
  "id": "user-123",
  "name": "Divakar",
  "email": "divakar@example.com",
  "age": 30
}
```

---

## Update a User Profile

```bash
curl -i -X PUT http://localhost:8080/api/user-profiles/user-123 \
  -H "Content-Type: application/json" \
  -d '{
        "id":"user-123",
        "name":"Divakar U",
        "email":"divakar@example.com",
        "age":31
      }'
```

---

## Delete a User Profile

```bash
curl -i -X DELETE http://localhost:8080/api/user-profiles/user-123
```

Expected Response:

```text
HTTP/1.1 204 No Content
```

---

## Inspect Data Directly in Redis

Verify the stored hash using Redis CLI:

```bash
docker exec -it redis-local redis-cli \
  HGETALL 'user:profile:{user-123}'
```

Example Output:

```text
1) "id"
2) "user-123"
3) "name"
4) "Divakar"
5) "email"
6) "divakar@example.com"
7) "age"
8) "31"
```

---

## Production Considerations

### Expiration

Temporary profiles can be configured with a TTL:

```java
repository.save(profile, Duration.ofHours(1));
```

This stores the hash and applies the expiration to the entire profile key. Calling
`save(profile)` without a TTL keeps the profile persistent.

The equivalent Redis command is:

```text
EXPIRE user:profile:{user-123} 3600
```

---

### Avoid KEYS in Production

Avoid:

```text
KEYS user:profile:*
```

because it scans the entire keyspace and can block Redis.

Use:

```text
SCAN
```

instead.

---

### Listing Profiles

Redis is optimized for key-based access.

If profile listing is required:

- Maintain a separate index.
- Use Redis Search.
- Store identifiers in a Set or Sorted Set.

---

### Durability

Redis can be used as:

- Cache
- Primary Database
- Hybrid Storage Layer

If Redis is the system of record, ensure:

- RDB snapshots are configured.
- AOF persistence is enabled.
- Replication is configured.
- Backups are tested.
- Recovery procedures are documented.

---

## Interview Notes

### Why use a Redis Hash instead of a String?

Hashes provide field-level access and updates without rewriting the entire object.

### What is the complexity of HGET?

```text
O(1)
```

### What is the complexity of HGETALL?

```text
O(N)
```

where:

```text
N = Number of fields in the hash
```

### When should Hashes be avoided?

- Large nested documents
- Complex search requirements
- Deeply hierarchical data structures

In such cases consider:

- RedisJSON
- Redis Search
- A dedicated document database
