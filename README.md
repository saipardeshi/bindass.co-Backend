# BINDASS.co — Backend

REST API for BINDASS, a premium unisex hoodie D2C brand. Built with Spring Boot + MongoDB.

## Tech Stack

- Java 17, Spring Boot 3.2.5
- MongoDB (Spring Data MongoDB)
- Spring Security + JWT
- Razorpay (payments) — HMAC-SHA256 signature verification
- Cloudinary (image hosting)

## Getting Started

1. Copy `.env.example` values into `src/main/resources/application.properties`
2. Run: `./mvnw spring-boot:run`
3. API at `http://localhost:5000`

## Security & Production Hardening

- **Auth Rate Limiting:** In-memory sliding window rate limiter protects `/login`, `/register`, `/forgot-password`, and `/resend-verification`.
- **Email Verification:** Complete token-based verification flow (`/api/auth/verify-email`, `/api/auth/resend-verification`).
- **Account Deletion:** GDPR/DPDP-compliant account wipe (`DELETE /api/auth/me`) with cart purge and order anonymization.
- **Database Backups:** Automated `mongodump` & `mongorestore` scripts with 7-day retention policy (see `docs/DATABASE_BACKUPS.md`).
- **Object-Level Security:** Strict user ownership verification on all order, cart, and profile operations.

## Known Gaps (in progress)

- No automated end-to-end integration tests yet
- No refresh token rotation
- No Docker/CI setup yet