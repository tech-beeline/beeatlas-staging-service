# ⚡ FastAPI SaaS Boilerplate

A production-ready Python backend starter kit for building SaaS applications fast.
Stop spending days on auth, billing, and project structure — start shipping your product.

---

## ✅ What's Included

| Feature | Details |
|---|---|
| **Authentication** | JWT (access + refresh tokens), register, login, logout |
| **User Management** | Profile CRUD, password update, soft delete |
| **Stripe Billing** | Checkout sessions, subscriptions, webhooks, cancellation |
| **Rate Limiting** | Per-route limits to prevent abuse |
| **Database** | SQLAlchemy ORM + Alembic migrations, SQLite out of the box, Postgres-ready |
| **Docker** | Dockerfile + docker-compose for instant deployment |
| **Tests** | pytest test suite included |
| **Type Safety** | Full Pydantic v2 schemas |

---

## 🚀 Quick Start

### Windows (CMD)

```cmd
:: 1. Create and activate virtual environment
python -m venv venv
venv\Scripts\activate

:: 2. Install dependencies
pip install -r requirements.txt

:: 3. Configure environment
copy .env.example .env

:: 4. Run database migrations
alembic upgrade head

:: 5. Start the server
uvicorn app.main:app --reload
```

### macOS / Linux

```bash
python -m venv venv
source venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
alembic upgrade head
uvicorn app.main:app --reload
```

API docs at: **http://localhost:8080/docs**

### Docker

```bash
docker-compose up --build
```

---

## 📡 API Endpoints

### Auth
| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/auth/register` | Create new account |
| POST | `/api/auth/login` | Login, get tokens |
| POST | `/api/auth/refresh` | Refresh access token |
| POST | `/api/auth/logout` | Logout |

### Users
| Method | Endpoint | Description |
|---|---|---|
| GET | `/api/users/me` | Get current user |
| PATCH | `/api/users/me` | Update profile |
| DELETE | `/api/users/me` | Deactivate account |
| GET | `/api/users/me/premium-data` | 🔒 Subscriber-only example |

### Billing
| Method | Endpoint | Description |
|---|---|---|
| POST | `/api/billing/create-checkout-session` | Start Stripe checkout |
| GET | `/api/billing/status` | Get subscription status |
| POST | `/api/billing/cancel-subscription` | Cancel at period end |
| POST | `/api/billing/webhook` | Stripe webhook handler |

---

## 🔒 Protecting Premium Routes

Use `get_current_active_subscriber` to lock any endpoint behind a paid subscription.
Returns `402 Payment Required` automatically if the user has no active plan.

```python
from app.core.security import get_current_active_subscriber

@router.get("/my-premium-feature")
def premium(current_user: User = Depends(get_current_active_subscriber)):
    return {"data": "only for paying customers"}
```

---

## 💳 Stripe Setup

1. Create a Stripe account at [stripe.com](https://stripe.com)
2. Create a product with a recurring price
3. Copy the Price ID (`price_...`) to your `.env`
4. Set up a webhook pointing to `/api/billing/webhook`
5. Copy the webhook secret (`whsec_...`) to your `.env`

---

## 🗄️ Database Migrations (Alembic)

```bash
# Apply all migrations
alembic upgrade head

# Create a new migration after changing models
alembic revision --autogenerate -m "add new field"

# Rollback one step
alembic downgrade -1
```

---

## 🐘 Switch to PostgreSQL

1. Uncomment the `db` service in `docker-compose.yml`
2. Update `.env`:
```
DATABASE_URL=postgresql://postgres:postgres@db:5432/saas_db
```

---

## 🧪 Run Tests

```cmd
:: Windows
venv\Scripts\activate
pytest tests/ -v
```

```bash
# macOS/Linux
pytest tests/ -v
```

---

## 🌐 Deploy Free on Render

1. Push to GitHub
2. New Web Service on [render.com](https://render.com)
3. Set env variables from `.env`
4. Deploy — free tier included

---

## 🛠️ Tech Stack

- **FastAPI** 0.111 — async Python web framework
- **SQLAlchemy** 2.0 — ORM
- **Alembic** — database migrations
- **Pydantic** v2 — data validation
- **python-jose** — JWT tokens
- **passlib + bcrypt** — password hashing
- **Stripe** — payments & subscriptions
- **SlowAPI** — rate limiting
- **pytest** — testing
- **Docker** — containerization

---

## 📄 License

MIT — use freely in personal and commercial projects.

---

## 📦 Free vs Full Package

This repository contains the **project structure, models, schemas, and configuration** as a preview.

The following modules are available in the **full package only**:

| Module | Free (GitHub) | Full Package |
|---|---|---|
| Project structure | ✅ | ✅ |
| SQLAlchemy models | ✅ | ✅ |
| Pydantic schemas | ✅ | ✅ |
| Docker setup | ✅ | ✅ |
| JWT Auth (full implementation) | Stub | ✅ |
| Stripe billing (full implementation) | Stub | ✅ |
| Alembic migrations | — | ✅ |
| pytest suite | — | ✅ |

👉 **Get the full package on [Gumroad](https://7538195787226.gumroad.com/l/cvdfl)**