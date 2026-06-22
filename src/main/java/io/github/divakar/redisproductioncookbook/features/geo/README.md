# Redis Geo: Restaurant Discovery Platform

## Introduction

This example demonstrates how to use Redis Geo to build a restaurant discovery platform.

Users can:

- Register restaurant locations
- Retrieve restaurant coordinates
- Calculate distance between restaurants
- Find nearby restaurants within a radius
- Perform location-based searches

Redis Geo provides efficient geospatial indexing and querying capabilities built on top of Redis Sorted Sets.

This makes Redis an excellent choice for:

- Food Delivery Platforms
- Ride Sharing Applications
- Store Locators
- Fleet Tracking
- Location-Based Recommendations

---

## What You'll Learn

- How Redis Geo works
- How GEOADD stores coordinates
- How GEOPOS retrieves coordinates
- How GEODIST calculates distance
- How GEOSEARCH finds nearby locations
- Geohash fundamentals
- Geo indexing internals
- Scaling location-based systems
- Production considerations for geo workloads

---

## Why Use Redis Geo?

Suppose a food delivery application needs to answer:

```text
Which restaurants are within 5 km of this customer?
```

Without Redis Geo:

```text
Read all restaurants
Calculate distance
Filter results
Sort by distance
Return nearest restaurants
```

With Redis Geo:

```text
GEOSEARCH
```

Redis performs the search using a geospatial index.

Benefits:

- Fast radius searches
- Distance calculations
- Built-in geo indexing
- Low latency lookups
- Memory efficient
- Simple API

---

## Why Not Store Coordinates in a Database?

Restaurant table:

```text
restaurant

id
name
latitude
longitude
```

To find nearby restaurants:

```text
Read all rows
Calculate distances
Sort results
Filter by radius
```

This becomes expensive as data grows.

Example:

```text
10 restaurants      -> Fine
10,000 restaurants  -> Expensive
1,000,000 locations -> Very expensive
```

Redis Geo maintains a geospatial index optimized for these queries.

---

## Real-World Use Cases

### Food Delivery

```text
Restaurants near me
```

### Ride Sharing

```text
Nearest available driver
```

### Store Locator

```text
Nearest store
```

### Logistics

```text
Track delivery vehicles
```

### Travel Applications

```text
Hotels near airport
```

---

## Redis Key Design

Restaurant locations:

```text
restaurants:locations
```

Restaurant identifiers:

```text
restaurant-101
restaurant-102
restaurant-103
```

Example:

```text
restaurants:locations

restaurant-101 -> Bangalore
restaurant-102 -> Whitefield
restaurant-103 -> Koramangala
```

---

## Architecture

```text
                     HTTP Requests
                           |
                           v
               +----------------------+
               | RestaurantController |
               +----------------------+
                           |
                           v
               +----------------------+
               | RestaurantRepository |
               +----------------------+
                           |
                           v
               +----------------------+
               | Redis Geo Index      |
               | restaurants:locations|
               +----------------------+
```

Flow:

```text
Restaurant Added
        |
        v
     GEOADD
        |
        v
  Geo Index Updated

Nearby Search
        |
        v
    GEOSEARCH
        |
        v
 Nearby Restaurants
```

---

## Redis Commands

| Command | Description |
|----------|-------------|
| GEOADD | Store coordinates |
| GEOPOS | Retrieve coordinates |
| GEODIST | Calculate distance |
| GEOSEARCH | Radius search |

---

## Example Commands

### Add Restaurant

```redis
GEOADD restaurants:locations \
77.5946 12.9716 restaurant-101
```

### Retrieve Coordinates

```redis
GEOPOS restaurants:locations restaurant-101
```

### Distance Between Restaurants

```redis
GEODIST restaurants:locations \
restaurant-101 \
restaurant-102 KM
```

### Search Nearby Restaurants

```redis
GEOSEARCH restaurants:locations \
FROMLONLAT 77.5946 12.9716 \
BYRADIUS 5 KM
```

---

## How Redis Geo Works Internally

Redis Geo is implemented using:

```text
Sorted Set
+
Geohash
```

Internally:

```text
Latitude + Longitude
        |
        v
      Geohash
        |
        v
 Sorted Set Score
```

Redis converts geographic coordinates into a Geohash value.

That Geohash is stored as the score in a Redis Sorted Set.

This allows Redis to efficiently search nearby locations.

---

## Geohash Deep Dive

A Geohash is a string representation of a geographic area.

Example:

```text
Bangalore

Latitude  : 12.9716
Longitude : 77.5946

Geohash: tdr1v9
```

Nearby locations share similar prefixes:

```text
tdr1v9
tdr1vb
tdr1vc
```

Redis uses this property to quickly identify nearby locations.
## Geohash Prefixes and Sorted Set Lookup

One of the key ideas behind Redis Geo is that nearby locations tend to share common Geohash prefixes.

Example:

```text
Restaurant A -> tdr1v9
Restaurant B -> tdr1vb
Restaurant C -> tdr1vc
```

Notice that all three locations start with:

```text
tdr1v
```

This indicates that they belong to the same geographic region.

As locations become closer together, they tend to share longer prefixes.

Example:

```text
tdr1v9x
tdr1v9y
tdr1v9z
```

These locations are much closer than:

```text
tdr1v9
tdr4ab
tdr8xy
```

---

### Geohash Precision

Longer geohashes represent smaller geographic areas.

| Geohash Length | Approximate Area |
|---------------|------------------|
| 1 | 5,000 km |
| 2 | 1,250 km |
| 3 | 156 km |
| 4 | 39 km |
| 5 | 4.9 km |
| 6 | 1.2 km |
| 7 | 150 m |
| 8 | 38 m |

Example:

```text
tdr1v
```

represents a much larger area than:

```text
tdr1v9x
```

which represents a very small region.

---

### How Redis Uses Geohashes

Internally Redis converts:

```text
Latitude + Longitude
```

into:

```text
Geohash
```

The Geohash is then converted into a sortable 52-bit integer.

Conceptually:

```text
Restaurant
      |
      v
Latitude / Longitude
      |
      v
    Geohash
      |
      v
52-bit Integer
      |
      v
Sorted Set Score
```

Redis stores all locations inside a Sorted Set.

Conceptually:

```text
Key: restaurants:locations

Score              Member
-----------------------------------
3479099956235      restaurant-101
3479099956241      restaurant-102
3479099956250      restaurant-103
```

The actual score values are encoded geohashes.

---

### Why Prefixes Matter

Suppose a user searches near:

```text
Latitude  : 12.9716
Longitude : 77.5946
```

Redis computes a geohash for the search location.

Example:

```text
tdr1v9
```

Redis can immediately narrow the search to nearby geohash ranges.

Instead of scanning:

```text
1,000,000 restaurants
```

Redis only examines locations that belong to neighboring geohash regions.

Conceptually:

```text
Entire Sorted Set
        |
        v
 Nearby Geohash Range
        |
        v
 Candidate Restaurants
        |
        v
 Exact Distance Calculation
        |
        v
 Final Results
```

This dramatically reduces the amount of work required.

---

### Neighbor Expansion

A common question is:

```text
What happens if a restaurant is just across a Geohash boundary?
```

Example:

```text
+-----------+-----------+
|  tdr1v8   |  tdr1v9   |
+-----------+-----------+
```

A nearby restaurant may fall into a neighboring Geohash cell.

Redis handles this by searching adjacent Geohash regions as well.

Conceptually:

```text
Current Cell

      +

Neighbor Cells

      =

Search Region
```

This ensures nearby locations are not missed near cell boundaries.

### Why Sorted Sets?

Sorted Sets provide:

```text

Range Queries

Ordered Scores

O(log N) Access

```

Geohashes naturally produce sortable values.

This makes Sorted Sets an ideal underlying structure for geographic indexing.

Interview answer:

```text

Redis Geo is implemented using a Sorted Set where the score is an encoded Geohash derived from latitude and longitude.

```

This design allows Redis to perform efficient proximity searches without scanning every location.

## Redis Geo vs Sorted Set

Many engineers do not realize:

```text
Redis Geo
=
Sorted Set
+
Geohash
```

Conceptually:

```text
Sorted Set
    score = numeric score

Geo
    score = geohash value
```

Redis simply exposes geo-specific commands on top of Sorted Sets.

---

## Geo Search Flow

```text
User Location
      |
      v
Redis Geo Index
      |
      v
Candidate Restaurants
      |
      v
Distance Sorting
      |
      v
Nearest Restaurants Returned
```

This avoids scanning all restaurants.

---

## Time Complexity

| Command | Complexity |
|----------|------------|
| GEOADD | O(log N) |
| GEOPOS | O(log N) |
| GEODIST | O(log N) |
| GEOSEARCH | O(log N + M) |

Where:

```text
N = Total Locations
M = Results Returned
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
| POST | /api/restaurants | Add restaurant |
| GET | /api/restaurants/{id}/location | Get coordinates |
| GET | /api/restaurants/distance | Calculate distance |
| GET | /api/restaurants/nearby | Find nearby restaurants |

---

## curl Examples

### Add Restaurant

```bash
curl -X POST http://localhost:8080/api/restaurants \
-H "Content-Type: application/json" \
-d '{
  "id":"restaurant-101",
  "name":"Biryani House",
  "latitude":12.9716,
  "longitude":77.5946
}'
```

Response:

```json
{
  "id":"restaurant-101",
  "name":"Biryani House",
  "latitude":12.9716,
  "longitude":77.5946
}
```

---

### Get Restaurant Location

```bash
curl \
http://localhost:8080/api/restaurants/restaurant-101/location
```

---

### Find Nearby Restaurants

```bash
curl \
"http://localhost:8080/api/restaurants/nearby?latitude=12.9716&longitude=77.5946&radiusKm=5"
```

Response:

```json
[
  "restaurant-101",
  "restaurant-102",
  "restaurant-103"
]
```

---

### Calculate Distance

```bash
curl \
"http://localhost:8080/api/restaurants/distance?from=restaurant-101&to=restaurant-102"
```

Response:

```json
{
  "from":"restaurant-101",
  "to":"restaurant-102",
  "distanceKm":2.4
}
```

---

## Inspect Data Directly in Redis

Add Restaurant:

```bash
docker exec redis-local redis-cli \
GEOADD restaurants:locations \
77.5946 12.9716 restaurant-101
```

Retrieve Coordinates:

```bash
docker exec redis-local redis-cli \
GEOPOS restaurants:locations restaurant-101
```

Search Nearby:

```bash
docker exec redis-local redis-cli \
GEOSEARCH restaurants:locations \
FROMLONLAT 77.5946 12.9716 \
BYRADIUS 5 KM
```

---

## Accuracy Considerations

Redis Geo is highly accurate for:

```text
Nearby Search
Store Locator
Food Delivery
Ride Sharing
```

However Redis Geo is not designed for:

```text
Property Boundaries
Polygon Search
Route Planning
Advanced GIS Analytics
```

Redis Geo uses geohashes and therefore performs approximate geographic indexing.

For most applications the approximation is negligible.

---

## Scaling Strategies

### City-Based Partitioning

Avoid:

```text
restaurants:locations
```

for global-scale deployments.

Prefer:

```text
restaurants:bangalore
restaurants:hyderabad
restaurants:mumbai
restaurants:chennai
```

Benefits:

- Smaller search space
- Better cache locality
- Reduced shard pressure
- Easier scaling

---

### Multi-Tenant Partitioning

For SaaS platforms:

```text
tenant:1:restaurants
tenant:2:restaurants
tenant:3:restaurants
```

instead of a giant shared index.

---

### Driver Tracking vs Static Locations

Restaurants:

```text
Mostly static
```

Drivers:

```text
Continuously moving
```

Example:

```text
Uber
Swiggy
Zomato
DoorDash
```

Drivers may update location every few seconds.

This creates significantly higher write throughput.

---

### Hot Region Problem

Some locations generate far more traffic.

Example:

```text
Bangalore City Center
```

vs

```text
Small Town
```

Popular urban regions may become hotspots.

Mitigation strategies:

- City partitioning
- Region partitioning
- Geohash partitioning
- Search result caching

---

### Caching Popular Searches

Popular searches often repeat.

Examples:

```text
Restaurants near Koramangala
Restaurants near Whitefield
Restaurants near MG Road
```

Caching these results can dramatically reduce query load.

---

## Production Considerations

### Radius Selection

Poor choice:

```text
100 KM radius
```

Good choices:

```text
Food Delivery -> 5 KM
Ride Sharing  -> 10 KM
Store Locator -> 25 KM
```

Larger radii increase result size and latency.

---

### Data Retention

Static locations:

```text
Restaurants
Stores
Warehouses
```

can live indefinitely.

Dynamic locations:

```text
Drivers
Vehicles
Delivery Partners
```

should expire automatically.

Example:

```text
driver-location:123
TTL = 5 minutes
```

---

### Location Update Frequency

Avoid updating location excessively.

Example:

```text
Every 100 milliseconds
```

Instead:

```text
Every 5-10 seconds
```

or when movement exceeds a threshold.

---

### Redis Geo vs GIS Systems

Redis Geo excels at:

```text
Nearby Search
Distance Calculations
Location Indexing
```

Redis Geo is not a replacement for:

```text
PostGIS
ArcGIS
Advanced GIS Platforms
```

---

### Redis Geo + PostGIS Pattern

A common architecture:

```text
Redis Geo
        |
        v
 Fast Nearby Search

PostGIS
        |
        v
 Advanced GIS Queries
```

Redis handles low-latency lookups.

PostGIS remains the system of record.

---

## Complete Example

Restaurants:

```text
restaurant-101
restaurant-102
restaurant-103
restaurant-104
```

Customer Location:

```text
Latitude  : 12.9716
Longitude : 77.5946
```

Search:

```redis
GEOSEARCH restaurants:locations \
FROMLONLAT 77.5946 12.9716 \
BYRADIUS 5 KM
```

Result:

```text
restaurant-101
restaurant-102
```

Only restaurants within 5 KM are returned.

---

## Key Takeaways

- Redis Geo is built on top of Sorted Sets.
- Redis Geo uses Geohash internally.
- GEOADD stores coordinates.
- GEOPOS retrieves coordinates.
- GEODIST calculates distance.
- GEOSEARCH performs radius searches.
- Geo indexes scale efficiently.
- Partitioning is critical at large scale.
- Redis Geo is ideal for nearby search workloads.

---

## Interview Notes

### How is Redis Geo implemented internally?

Redis Geo is implemented using:

```text
Sorted Set
+
Geohash
```

Coordinates are encoded into geohashes and stored as Sorted Set scores.

---

### Why is Redis Geo fast?

Redis uses geospatial indexing rather than scanning every location.

Nearby searches operate on indexed geohash ranges.

---

### What commands are commonly used?

```redis
GEOADD
GEOPOS
GEODIST
GEOSEARCH
```

---

### When should you use Redis Geo?

- Food Delivery
- Ride Sharing
- Store Locator
- Fleet Tracking
- Nearby Search

---

### When is Redis Geo not sufficient?

When you need:

- Route Planning
- Polygon Queries
- Geofencing
- Advanced GIS Analytics

Use PostGIS or another GIS platform.

---

### How would you scale Redis Geo?

- Partition by city
- Partition by region
- Partition by tenant
- Cache popular searches
- Separate static and dynamic locations

---

### What is the Hot Region Problem?

A small geographic area may generate disproportionate traffic.

Examples:

```text
City Centers
Airports
Shopping Districts
```

Partitioning and caching help distribute load.

## Advanced Geospatial Index Comparison

![Geospatial Index Cheat Sheet](/docs/images/redis-geospatial-indexes.png)

Redis Geo is not the only geospatial indexing strategy.

Different systems optimize for different workloads.

Understanding the trade-offs helps choose the right technology.

---

### Comparison Matrix

| Technology | Best For | Strengths | Weaknesses |
|------------|-----------|-----------|------------|
| Redis Geo | Nearby Search | Very fast, simple, in-memory | Limited GIS functionality |
| QuadTree | Games, Maps | Adapts to density | More complex implementation |
| R-Tree | Polygons, Shapes | Excellent for geometric objects | More expensive operations |
| BKD Tree | Multi-dimensional search | Powerful filtering | More complex |
| Grid / Geocell | Heatmaps, Aggregations | Simple partitioning | Lower precision |
| PostGIS | GIS workloads | Rich spatial functions | Higher latency |
| Elasticsearch Geo | Geo + Text Search | Search + Geo combined | Larger infrastructure footprint |

---

## Redis Geo

Implementation:

```text
Geohash
+
Sorted Set
```

Best for:

```text
Nearby Restaurants
Nearby Drivers
Store Locator
Food Delivery
Ride Sharing
```

Typical Question:

```text
Who is near me?
```

Advantages:

- Simple
- Fast
- In-memory
- Easy to operate

Limitations:

- No polygon queries
- No route planning
- No advanced GIS analytics

---

## QuadTree

QuadTree recursively divides space into four regions.

Example:

```text
+---------+
|    |    |
|----+----|
|    |    |
+---------+
```

Dense regions are split further.

Best for:

```text
Maps
Game Engines
Collision Detection
Spatial Simulations
```

Advantages:

- Handles uneven data distribution
- Efficient region queries
- Good for dynamic maps

Used by:

```text
Game Engines
Mapping Systems
```

---

## R-Tree

R-Tree stores bounding rectangles.

Example:

```text
Building
Park
Delivery Zone
City Boundary
```

Instead of points.

Best for:

```text
Polygon Search
Geofencing
Shape Intersections
Spatial Joins
```

Advantages:

- Excellent for geometric objects
- Supports complex shapes

Used by:

```text
PostGIS
GIS Systems
```

Typical Question:

```text
Which delivery zone contains this point?
```

---

## BKD Tree

BKD Tree is a multidimensional index.

Example dimensions:

```text
Latitude
Longitude
Rating
Price
Delivery Time
```

Instead of searching only by location:

```text
Nearby Restaurant
```

you can search:

```text
Nearby Restaurant
AND
Rating > 4.5
AND
Price < 500
```

Advantages:

- Multi-dimensional filtering
- Excellent for search workloads

Used by:

```text
Elasticsearch
OpenSearch
Lucene
```

Typical Question:

```text
Find nearby restaurants
with rating > 4.5
and delivery time < 30 minutes
```

---

## Grid / Geocell Index

World divided into fixed-size cells.

Example:

```text
+---+---+---+
| A | B | C |
+---+---+---+
| D | E | F |
+---+---+---+
```

Best for:

```text
Heatmaps
Analytics
Counting
Aggregations
```

Advantages:

- Simple
- Easy to shard
- Great for reporting

Limitations:

- Lower precision
- Cell boundary issues

---

## PostGIS

PostGIS extends PostgreSQL with GIS capabilities.

Best for:

```text
Polygon Queries
Geofencing
Routing
Spatial Analytics
Land Parcels
```

Example Questions:

```text
Which delivery zone contains this address?

Which stores lie within this city boundary?

What route should the driver take?
```

Advantages:

- Rich GIS functions
- Industry standard

Limitations:

- Higher latency than Redis
- More operational complexity

---

## Elasticsearch Geo

Elasticsearch combines:

```text
Geo Search
+
Full Text Search
```

Example:

```text
Restaurants near me
serving biryani
rated above 4.5
```

Advantages:

- Geo + Search
- Rich filtering
- Scalable

Used by:

```text
Airbnb
LinkedIn
E-commerce Search
```

---

## How Scaling Differs

### Redis Geo

Scale by:

```text
City Partitioning

restaurants:bangalore
restaurants:mumbai
restaurants:hyderabad
```

---

### QuadTree

Scale by:

```text
Recursive subdivision
```

Dense areas get more partitions.

---

### R-Tree

Scale by:

```text
Hierarchical bounding rectangles
```

---

### BKD Tree

Scale by:

```text
Dimension pruning
```

Only relevant branches are visited.

---

### Grid Index

Scale by:

```text
Cell partitioning
```

Easy to distribute across shards.

---

## Which One Should You Choose?

### Nearby Restaurants

```text
Redis Geo
```

### Nearby Drivers

```text
Redis Geo
```

### Store Locator

```text
Redis Geo
```

### Game World Map

```text
QuadTree
```

### Delivery Zones

```text
R-Tree / PostGIS
```

### Geo + Text Search

```text
Elasticsearch
```

### GIS Analytics

```text
PostGIS
```

### Heatmaps and Aggregations

```text
Grid Index
```

---

## Interview Answer

If asked:

```text
Why Redis Geo instead of PostGIS?
```

A strong answer is:

```text
Redis Geo is optimized for low-latency proximity searches using Geohashes stored in Sorted Sets.

For nearby restaurant, driver, or store lookups, Redis provides extremely fast in-memory queries.

If advanced GIS capabilities such as polygons, routing, or geofencing are required, PostGIS is a better choice.
```