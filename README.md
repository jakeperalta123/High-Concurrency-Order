# High-Concurrency Seckill & Order Processing System

## Project Overview

This project is a high-concurrency, low-latency, and highly reliable flash-sale and order processing system built on **Spring Boot 3.x, Redis, Kafka, and MySQL**. Designed around **First-Principles Thinking** and a **Layered Defense-in-Depth Architecture**, the core architectural philosophy centers on: **"Cache Fronting, Asynchronous Peak-Shaving, Physical Isolation, and Persistent Storage Write-Back."**

By discarding traditional heavyweight approaches—such as direct database hits or JVM-level pessimistic locking—this system guarantees **zero over-selling, eventual consistency, strict rate-limiting against malicious bots, and robust protection against cache penetration** under extreme traffic spikes.

---

## 1. System Topology & Network Architecture

The entire infrastructure is fully containerized and decoupled within an isolated bridge network named `seckill-network`. Through optimized internal and external listener alignment, it achieves high-efficiency microservice communication and clear observability:

```
                                [ External Clients / Testing Tools (JMeter) ]
                                                     │
                          ┌──────────────────────────┴──────────────────────────┐
                          │ Port 8080 (REST API)     │ Port 6379 (Cache Obs.)   │ Port 9092 (External Broker)
                          ▼                          ▼                          ▼
               ┌───────────────────┐      ┌───────────────────┐      ┌───────────────────┐
               │    seckill-app    │      │   seckill-redis   │      │   seckill-kafka   │
               │   (Spring Boot)   │      │ (Redis 7.2-Alpine)│      │(Kafka 7.5.0 KRaft)│
               └─────────┬─────────┘      └─────────▲─────────┘      └─────────▲─────────┘
                         │                          │                          │
                         │ Port 3306 (Internal DB)  │ Port 6379 (Internal Mesh)│ Port 29092 (Internal Broker)
                         ▼                          │                          │
               ┌───────────────────┐                │                          │
               │   seckill-mysql   ├────────────────┴──────────────────────────┘
               │    (MySQL 8.0)    │ ◄─── (Asynchronous Worker Write-Back to DB)
               └───────────────────┘

```

---

## 2. End-to-End Seckill Data Flow Diagram

The diagram below illustrates the physical execution path of data and component interactions when processing high-volume flash-sale traffic:

```
[ User Client ]
     │
     │ STEP 1. Fetch Dynamic Mathematical Verification Code (Block Automated Bots)
     ├───> [ SeckillController.getSeckillVerifyCode ]
     │        ├───> [ Redisson BloomFilter ] (Intercept non-existent Product IDs; Prevent Cache Penetration)
     │        └───> [ Redis ] Store answer with TTL (2 Mins) ──> Return Base64 Captcha Image
     │
     │ STEP 2. Submit Answer & Acquire Dynamic Hidden Order Path (Prevent URL Exposing / Front-Running)
     ├───> [ SeckillController.getSeckillPath ]
     │        └───> [ Redis ] Validate & Evict Captcha ──> Sign random PathId via MD5(Salt + UUID) [TTL: 5 Mins]
     │
     │ STEP 3. Fire Official Seckill Request (POST /api/seckill/{pathId}/order)
     └───> [ SeckillController.placeOrderV4 ]
              ├───> [ Redisson BloomFilter ] (Secondary Security Verification)
              ├───> [ Redis + rate_limit.lua ] (Distributed Token Bucket Rate Limiter)
              ├───> [ Redis + deduct_stock.lua ] (Execute Lua Script for Single-Threaded Atomic Stock Pre-deduction)
              │        │
              │        ├───> [Sufficient Stock] (remainStock >= 0)
              │        │        └───> [ SeckillOrderProducer ] Route message to Kafka Partition using ProductId as Key
              │        │              └───> Immediate HTTP 200 Response: {"code": 200, "msg": "In queue, please wait..."}
              │        │
              │        └───> [Out of Stock] (remainStock = -2) 
              │                 └───> Traffic dropped instantly at Cache Layer ──> Response: Out of Stock
              :
[ Kafka Partition Queue ] (Topic: seckill-order-topic) ──── (Ordered strictly by ProductId Key per Partition)
     │
     │ STEP 4. Asynchronous Peak-Shaving & Persistence Workers
     └───> [ SeckillOrderConsumer ] (Consumer Group: seckill-order-group-v2)
              └───> [ OrderService.handleDBPersistence ] (Execute within MySQL Local Transaction)
                       ├───> Deduct MySQL Physical Stock (WHERE stock >= quantity)
                       ├───> Create Order Record
                       ├───> Write StockLog (For auditing and compensating transactions)
                       │
                       └───> [ Ultimate Idempotency Shield ] 
                                └───> If Kafka retries delivery due to network jitter, MySQL triggers 
                                      `DuplicateKeyException` via UNIQUE Constraint (order_sn) ──> Catch & Ack

```

---

## 3. Core Technical Highlights & Deep Dives

### 3.1 Cache Warm-Up & Penetration Mitigation

During system bootstrapping, `StockPreloadRunner` queries inventory data from MySQL and warms up the Redis cache.

* **Cache Avalanche Prevention**: To prevent massive simultaneous cache expirations from hammering the database, keys are configured with a base TTL (7 days) plus a randomized offset ($0 \sim 12$ hours).
* **Bloom Filter Integration**: A bit-matrix is initialized via `bloomFilter.tryInit(10000L, 0.03)` with a 3% false-positive tolerance. Malicious scans targeting non-existent Product IDs are short-circuited at the outermost edge, shielding the downstream infrastructure.

### 3.2 Thread-Safe Atomic Stock Deduction (`deduct_stock.lua`)

To eliminate concurrency anomalies (such as over-selling) and bypass the heavy thread-blocking overhead associated with distributed locks, inventory validation and deduction are bundled into a single Lua script:

```lua
local stock = redis.call('get', KEYS[1])
if not stock then return -1 end -- Cache Miss / Invalid Key Flag

stock = tonumber(stock)
local amount = tonumber(ARGV[1])

if stock >= amount then 
    return redis.call('decrby', KEYS[1], amount) -- Atomic pre-deduction, returns remaining stock
else
    return -2 -- Insufficient Stock Flag
end

```

**Under the Hood**: Redis guarantees **Atomicity** when executing Lua scripts. Because of its single-threaded execution model, this operations achieves exclusive isolation with an $O(1)$ complexity without requiring any heavy Distributed Locks (e.g., Redisson Locks).

### 3.3 Token Bucket Distributed Rate Limiting (`rate_limit.lua`)

The system incorporates a Redis Hash-backed token bucket algorithm that dynamically recalculates token replenishment based on temporal deltas ($\Delta t$). This protects the application layer from collapsing under sudden, massive traffic surges, providing circuit-breaking and elasticity.

### 3.4 Double-Check & Dynamic Cache Reconstruction Mechanism

To handle sudden `Cache Misses` gracefully without causing data store outages, the system utilizes a defensive reconstruction pipeline (`deductRedisStock`):

1. If the Lua script returns `-1` (Cache Miss), the system blocks wildcard database access.
2. A fine-grained distributed lock is requested via `RLock lock = redissonClient.getLock(...)` mapped to the specific product ID.
3. The **single thread** that acquires the lock queries MySQL, updates the Redis cache with a randomized TTL, and proceeds.
4. Threads that fail to acquire the lock invoke `Thread.sleep(100)` and re-attempt the cache lookup, eliminating **Cache Breakdown (Thundering Herd Problem)**.

### 3.5 Cache Consistency via Delayed Double Delete

When administrative actions modify non-frequently changing product attributes (e.g., updating a base price via `ProductServiceImplement`), a **Delayed Double Delete** strategy is enforced to guarantee eventual consistency across distributed nodes:

```java
redisTemplate.delete(redisKey); // 1. Evict cache initially
productMapper.updateProductPriceById(newPrice, productId); // 2. Update DB
delayedDeletePool.execute(() -> {
    try {
        Thread.sleep(500); // 3. Asynchronously wait for 500ms (Database replication lag window)
        redisTemplate.delete(redisKey); // 4. Secondary eviction to wipe out any stale data cached in the interim
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }
});

```

### 3.6 Eventual Consistency & Idempotency Shield

Once traffic is rate-limited and validated at the Redis layer, order events are wrapped into a `SeckillOrderMessage` payload and streamed to Apache Kafka.

* **Partition-Level Ordering**: Messages are dispatched using `String.valueOf(productId)` as the **Partition Key**. According to Kafka’s routing design, all order events for a given product map to the same partition, ensuring that downstream workers process them chronologically.
* **Storage-Level Idempotency**: The `orders` table implements a **UNIQUE Index Constraint** on the `order_sn` field. If Kafka triggers its `At Least Once` delivery mechanism due to network flakiness, Spring Boot intercepts the resulting `DuplicateKeyException`:

```java
log.warn("⚠️ [IDEMPOTENCY DETECTED] Order [{}] already exists in MySQL", orderSn);
ack.acknowledge(); // Commit offset immediately, dropping the duplicate event safely

```

---

## 4. Local Deployment

### Prerequisites

1. Ensure Docker Desktop is installed and running (verify that local ports `6379`, `3306`, `8080`, and `9092` are not bound by other processes).
2. Create a `.env` file in the root directory and populate it with your security credentials:

```env
DB_PASSWORD=your_secure_db_password
JWT_SECRET=your_jwt_secret_key
JWT_SALT=your_obfuscation_salt
JWT_EXPIRATION=86400000

```

### Spin Up Infrastructure

Execute the following command from the project root to build and deploy all services in detached mode:

```bash
docker compose up -d --build

```

### Monitor Application Status

To verify the bootstrap progress and track Kafka/Flyway migrations in real-time, inspect the container logs:

```bash
docker compose logs -f seckill-app

```

---
