from pydantic import BaseModel, EmailStr
from typing import Optional
from datetime import datetime


# Auth
class Token(BaseModel):
    access_token: str
    refresh_token: str
    token_type: str = "bearer"


class TokenRefresh(BaseModel):
    refresh_token: str


# User
class UserBase(BaseModel):
    email: EmailStr
    full_name: Optional[str] = None


class UserCreate(UserBase):
    password: str


class UserUpdate(BaseModel):
    full_name: Optional[str] = None
    password: Optional[str] = None


class UserResponse(UserBase):
    id: int
    is_active: bool
    is_subscribed: bool
    subscription_status: Optional[str]
    created_at: datetime

    class Config:
        from_attributes = True


# Billing
class CheckoutSession(BaseModel):
    checkout_url: str


class SubscriptionStatus(BaseModel):
    is_subscribed: bool
    subscription_status: Optional[str]
    stripe_subscription_id: Optional[str]
