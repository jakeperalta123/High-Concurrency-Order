# High-Concurrency Seckill & Order Processing System

## Project Overview

This project is a high-concurrency, low-latency, and highly reliable flash-sale and order processing system built on **Spring Boot 3.x, Redis, Kafka, and MySQL**. Designed around **First-Principles Thinking** and a **Layered Defense-in-Depth Architecture**, the core architectural philosophy centers on: **"Cache Fronting, Asynchronous Peak-Shaving, Physical Isolation, and Persistent Storage Write-Back."**

By discarding traditional heavyweight approaches—such as direct database hits or JVM-level pessimistic locking—this system guarantees **zero over-selling, eventual consistency, strict rate-limiting against malicious bots, and robust protection against cache penetration** under extreme traffic spikes.

---

## 1. System Topology & Network Architecture

The entire infrastructure is fully containerized and decoupled within an isolated bridge network named `seckill-network`. Through optimized internal and external listener alignment, it achieves high-efficiency microservice communication and clear observability:

```text
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

```text
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

## 4. Load Testing & JVM Performance Metrics (Real Telemetry Data)

To stress-test the resilience of our asynchronous decoupled architecture, a massive high-load simulation was executed via **Apache JMeter**, with real-time JVM metrics captured using **JConsole**.

### Load Test Profile (JMeter Configuration)
<img width="1339" height="579" alt="Image" src="https://github.com/user-attachments/assets/8c3c4ed1-7ebc-47fa-ad73-8ce9148e9f59" />
* **Thread Group**: `3,000` concurrent users (threads) injected within a `1-second` ramp-up window.
* **Execution**: Sustained burst traffic to simulate real-world flash-sale thundering herd behavior.
* **Result**: **100% data consistency** across Redis and MySQL. Initial stock of 5,000 perfectly processed down to 2,000 with **zero over-selling, zero dropped messages, and zero errors**.

---

### Deep Dive into JVM Metrics (Captured via JConsole)

#### 1. Heap Memory Usage — *The Elastic Sawtooth Pattern*
<img width="1339" height="666" alt="Image" src="https://github.com/user-attachments/assets/471e06f8-2eef-4ab6-8c34-cf9e67c0ba24" />
* **Observation**: Upon firing the load test, the JVM heap memory (`Used Heap`) climbed swiftly from a baseline of `150MB` up to a peak of approximately `390MB`. Immediately following the conclusion of the test, a GC event triggered a **sharp vertical drop**, plummeting the heap back down to less than `80MB`.
* **Architectural Insight**: This rapid rise followed by a near-instantaneous plunge demonstrates an exceptionally healthy JVM ecosystem. It proves that high-concurrency short-lived components (DTOs, JSON strings, HTTP request objects) lose their references immediately post-execution. The **G1 Garbage Collector** seamlessly reclaims over 300MB of temporary garbage within milliseconds, keeping the Old Generation clean and entirely avoiding memory leaks.

#### 2. Thread Pool Telemetry — *Elastic Lifecycle Management*
<img width="1333" height="666" alt="Image" src="https://github.com/user-attachments/assets/17fec5c1-6d5f-4970-b253-a6bed46ebb1d" />
* **Observation**: Under normal idle operations, the system maintains around `86` active threads. During the peak concurrent spike, live threads expanded dynamically to a peak of `280`, then smoothly decelerated back down to the `86`-thread baseline.
* **Architectural Insight**: This maps perfectly to the expected behavior of the embedded Tomcat executive executor thread pool. Faced with 3,000 concurrent network sockets, Tomcat rapidly scales its worker thread pool to absorb the shockwave. Once the traffic subsides, idle worker threads hit their keep-alive timeouts and are gracefully destroyed, preventing thread deadlocks or resource starvation.

#### 3. Class Loading Telemetry — *Rock-Solid Metaspace Baseline*
<img width="1333" height="662" alt="Image" src="https://github.com/user-attachments/assets/b9ec88ee-545e-4583-9af4-2520108a1306" />
* **Observation**: The total loaded class count remained absolutely static at `19,119` classes, resulting in a dead-flat horizontal line on the telemetry graph.
* **Architectural Insight**: Operating enterprise-grade frameworks (Spring Boot, MyBatis-Plus, Kafka Clients, Redisson) inherently introduces an industry-standard metadata footprint (~1.9万 classes). The completely flat line verifies that under extreme concurrent load, the application is not triggering memory leaks in the Metaspace/Classloader layer via un-cached dynamic proxies or leaky class loaders.

#### 4. CPU Usage Analysis — *The Asynchronous Decoupling Advantage*
<img width="1636" height="844" alt="Image" src="https://github.com/user-attachments/assets/e4af2661-ee16-4208-86bd-98d73749b027" />
* **Observation**: Despite handling a massive 3,000-user thundering herd, the JVM's CPU usage experienced only a minor, transient spike fluctuating between `1.5%` and `20%` during thread context creation. For the remainder of the lifecycle, the CPU cruised at near-idle thresholds.
* **Architectural Insight**: This is the ultimate proof of **"Redis Pre-deduction + Kafka Asynchronous Peak-Shaving"** at work! The Web layer (`Tomcat`) handles lightweight, fast I/O bound requests—validating the request via Lua and instantly dispatching the payload into a Kafka broker before returning an immediate generic queueing response. The heavy CPU-bound compute tasks (ACID transactional DB writes) are safely offloaded to background consumer threads, drastically lowering CPU load and maximizing horizontal scalability.

---

## 5. Core Interview Architecture Highlights (STAR Framework)

* **Situation**: Needed to engineer a backend order system capable of sustaining a 3,000+ thread flash-sale surge without crashing the physical database or over-selling stock.
* **Task**: Ensure zero over-selling, maintain eventual consistency, implement high-performance rate-limiting, and safeguard system resources under minimal CPU overhead.
* **Action**: Configured a JVM heap envelope (`-Xms512m -Xmx512m`) with `G1GC` to enforce deterministic runtime behavior. Built a layered defense pipeline: **Bloom Filter** for penetration mitigation, **Token Bucket Lua** for rate-limiting, **Atomic Redis Lua** for high-efficiency stock subtraction, and **Kafka partitioned streams** (keyed by product ID) for sequential asynchronous write-backs backed by a MySQL unique constraint.
* **Result**: Validated via JMeter and monitored via JConsole. Achieved 100% database accuracy with zero concurrency leaks. Demonstrated a clean sawtooth heap pattern (390MB peak cleared down to 80MB) and restricted CPU utilization below 20%, proving low compute overhead and robust infrastructure stability.

---

## 6. Local Deployment

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
