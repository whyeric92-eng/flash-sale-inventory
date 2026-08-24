# Flash Sale Inventory System

A flash-sale / anti-oversell inventory system built with Spring Boot and Redis, deployed on a self-managed Linux VPS.

## Tech Stack

- **Backend**: Spring Boot 4 (Spring Web, Spring Data JPA, Spring Security + JWT)
- **Database**: PostgreSQL (source of truth for business data)
- **Cache / Concurrency Control**: Redis (cache-aside for product info; atomic stock decrement via DECR / Lua scripts or Redisson distributed lock; List for async order queue)
- **Deployment**: Self-managed Linux VPS, Docker + docker-compose (local dev), nginx reverse proxy, systemd
- **Load Testing**: wrk / ab


## Status

🚧 In progress. 
