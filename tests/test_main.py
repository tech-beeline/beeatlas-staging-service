import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from app.main import app
from app.core.database import Base, get_db

# Test database
SQLALCHEMY_DATABASE_URL = "sqlite:///./test.db"
engine = create_engine(SQLALCHEMY_DATABASE_URL, connect_args={"check_same_thread": False})
TestingSessionLocal = sessionmaker(autocommit=False, autoflush=False, bind=engine)


def override_get_db():
    db = TestingSessionLocal()
    try:
        yield db
    finally:
        db.close()


app.dependency_overrides[get_db] = override_get_db
Base.metadata.create_all(bind=engine)
client = TestClient(app)


def test_health():
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_register():
    response = client.post("/api/auth/register", json={
        "email": "test@example.com",
        "password": "testpassword123",
        "full_name": "Test User"
    })
    assert response.status_code == 201
    assert response.json()["email"] == "test@example.com"


def test_register_duplicate():
    client.post("/api/auth/register", json={
        "email": "duplicate@example.com",
        "password": "testpassword123",
    })
    response = client.post("/api/auth/register", json={
        "email": "duplicate@example.com",
        "password": "testpassword123",
    })
    assert response.status_code == 400


def test_login():
    client.post("/api/auth/register", json={
        "email": "login@example.com",
        "password": "testpassword123",
    })
    response = client.post("/api/auth/login", data={
        "username": "login@example.com",
        "password": "testpassword123",
    })
    assert response.status_code == 200
    assert "access_token" in response.json()
    assert "refresh_token" in response.json()


def test_get_me():
    client.post("/api/auth/register", json={
        "email": "me@example.com",
        "password": "testpassword123",
    })
    login = client.post("/api/auth/login", data={
        "username": "me@example.com",
        "password": "testpassword123",
    })
    token = login.json()["access_token"]
    response = client.get("/api/users/me", headers={"Authorization": f"Bearer {token}"})
    assert response.status_code == 200
    assert response.json()["email"] == "me@example.com"
