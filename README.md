# BINDASS.co — Backend

REST API for BINDASS, a premium unisex hoodie D2C brand. Built with Spring Boot + MongoDB.

## Tech Stack

- Java 17+, Spring Boot 3.2.5
- MongoDB (Spring Data MongoDB)
- Spring Security + JWT
- Razorpay (payments) — HMAC-SHA256 signature verification, payment bound to its order
- Cloudinary (image hosting)

## Getting Started

1. `cp .env.example .env` and fill it in. **`.env` is git-ignored — never commit it.**
   Real environment variables (`SPRING_DATA_MONGODB_URI`, `APP_JWT_SECRET`, …) override `.env`.
2. Generate a JWT secret: `openssl rand -base64 48` → `app.jwt.secret` in `.env`.
   The app **refuses to start** if it is missing, shorter than 32 chars, or a placeholder.
3. Run: `./mvnw spring-boot:run` — API at `http://localhost:5000`
4. Tests: `./mvnw test` (pure unit tests, no database needed)

`application.properties` is committed and contains **no secrets**.

### Optional startup data (off by default)

| Setting | Effect |
|---|---|
| `app.seed.enabled=true` | Inserts the starter catalogue (`src/main/resources/seed/products.json`). Idempotent — never overwrites existing products. |
| `app.admin.email` + `app.admin.password` | Creates the **first admin** if no admin exists (password ≥ 10 chars). Remove after first start. |
| `app.rate-limit.trust-proxy=true` | Honour `X-Forwarded-For` for rate limiting. Only enable behind your own reverse proxy. |

## Security & Production Hardening

- **Payments:** a payment signature is only accepted for the order it was issued for; orders are claimed atomically (`PENDING → CONFIRMED`), so a payment cannot be replayed onto another order or confirm a cancelled one.
- **Stock:** reserved with an atomic conditional update at payment time and restored only if it was actually taken. A stock shortage after payment is flagged in the order history for manual handling.
- **Cancellations:** paid orders are refunded; a failed refund marks the order `REFUND_FAILED` instead of leaving it looking paid.
- **Auth Rate Limiting:** sliding-window limiter on login, register, forgot/reset-password, resend-verification and newsletter, keyed on the real client IP.
- **Validation:** nested request objects are validated (`@Valid`); malformed input returns 400/415 instead of 500.
- **Status codes:** 401 = not signed in, 403 = signed in but not allowed (ownership or role).
- **Email Verification:** token-based flow (`/api/auth/verify-email`, `/api/auth/resend-verification`).
- **Account Deletion:** wipe (`DELETE /api/auth/me`) with cart + review purge and order anonymization.
- **Database Backups:** `mongodump` / `mongorestore` scripts with 7-day retention (see `docs/DATABASE_BACKUPS.md`).

## API notes

- All responses: `{ "success": bool, "message": string, "data": … }`.
- `GET /api/products` — `page` (≥0), `size` (1–50), `sort` (`newest|price_asc|price_desc|popular|rating`), `minPrice`, `maxPrice`, `sizes=M,L`, `sale`, `q`. All filters combine.
- `GET /api/products/search?q=` — name / tag / category search.
- `PUT /api/auth/profile` — update name, phone, saved addresses.

## Known Gaps

- No refresh-token rotation
- No Docker Compose / CI pipeline yet
- Spring Boot 3.2.x is past community support — plan an upgrade to a supported line
- Razorpay webhooks are not implemented (payment confirmation relies on the browser callback)
