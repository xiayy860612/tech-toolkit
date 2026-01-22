"""Tests for User model."""
from datetime import datetime, timezone

import pytest
from sqlalchemy import select

from src.models.user import User


@pytest.mark.asyncio
async def test_user_creation(test_db) -> None:
    """Test that a User can be created and retrieved."""
    user = User(
        username="testuser",
        hashed_password="hashed_password_here",
        display_name="Test User",
        avatar_url="/avatars/test.png",
        role="student",
    )

    test_db.add(user)
    await test_db.commit()
    await test_db.refresh(user)

    assert user.id is not None
    assert user.username == "testuser"
    assert user.display_name == "Test User"
    assert user.role == "student"
    assert user.is_active is True


@pytest.mark.asyncio
async def test_user_unique_username_constraint(test_db) -> None:
    """Test that username must be unique."""
    user1 = User(
        username="duplicate",
        hashed_password="hash1",
        display_name="User 1",
        role="student",
    )

    user2 = User(
        username="duplicate",
        hashed_password="hash2",
        display_name="User 2",
        role="student",
    )

    test_db.add(user1)
    await test_db.commit()

    test_db.add(user2)
    with pytest.raises(Exception):  # IntegrityError
        await test_db.commit()


@pytest.mark.asyncio
async def test_user_timestamps(test_db) -> None:
    """Test that created_at and updated_at timestamps are set."""
    user = User(
        username="timetest",
        hashed_password="hash",
        display_name="Time Test",
        role="student",
    )

    test_db.add(user)
    await test_db.commit()
    await test_db.refresh(user)

    assert user.created_at is not None
    assert user.updated_at is not None
    assert isinstance(user.created_at, datetime)
    assert isinstance(user.updated_at, datetime)


@pytest.mark.asyncio
async def test_user_default_values(test_db) -> None:
    """Test that default values are set correctly."""
    user = User(
        username="defaults",
        hashed_password="hash",
        display_name="Default User",
    )

    test_db.add(user)
    await test_db.commit()
    await test_db.refresh(user)

    assert user.is_active is True
    assert user.role == "user"  # Default role
    assert user.avatar_url is None
