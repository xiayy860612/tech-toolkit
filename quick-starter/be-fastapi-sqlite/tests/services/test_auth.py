"""Tests for AuthService."""
import pytest

from src.core.security import verify_password, get_password_hash
from src.models.user import User
from src.services.auth import AuthService


@pytest.mark.asyncio
async def test_authenticate_user_with_valid_credentials(test_db) -> None:
    """Test that a user can be authenticated with valid credentials."""
    # Create a test user
    hashed = get_password_hash("testpass123")
    user = User(
        username="authuser",
        hashed_password=hashed,
        display_name="Auth User",
        role="student",
    )

    test_db.add(user)
    await test_db.commit()

    # Test authentication with valid credentials
    auth_service = AuthService()
    result = await auth_service.authenticate_user(test_db, "authuser", "testpass123")

    assert result is not None
    assert result.username == "authuser"
    assert result.display_name == "Auth User"


@pytest.mark.asyncio
async def test_authenticate_user_with_invalid_password(test_db) -> None:
    """Test that authentication fails with invalid password."""
    # Create a test user
    hashed = get_password_hash("correctpass")
    user = User(
        username="authuser2",
        hashed_password=hashed,
        display_name="Auth User 2",
        role="student",
    )

    test_db.add(user)
    await test_db.commit()

    # Test authentication with invalid password
    auth_service = AuthService()
    result = await auth_service.authenticate_user(test_db, "authuser2", "wrongpass")

    assert result is None


@pytest.mark.asyncio
async def test_authenticate_nonexistent_user(test_db) -> None:
    """Test that authentication fails for nonexistent user."""
    auth_service = AuthService()
    result = await auth_service.authenticate_user(test_db, "nonexistent", "anypassword")

    assert result is None


@pytest.mark.asyncio
async def test_authenticate_inactive_user(test_db) -> None:
    """Test that authentication fails for inactive user."""
    # Create an inactive test user
    hashed = get_password_hash("testpass123")
    user = User(
        username="inactiveuser",
        hashed_password=hashed,
        display_name="Inactive User",
        role="student",
        is_active=False,
    )

    test_db.add(user)
    await test_db.commit()

    # Test authentication with inactive user
    auth_service = AuthService()
    result = await auth_service.authenticate_user(test_db, "inactiveuser", "testpass123")

    assert result is None


@pytest.mark.asyncio
async def test_get_user_by_username(test_db) -> None:
    """Test getting user by username."""
    # Create a test user
    user = User(
        username="finduser",
        hashed_password="hash",
        display_name="Find User",
        role="teacher",
    )

    test_db.add(user)
    await test_db.commit()

    # Test getting user by username
    auth_service = AuthService()
    result = await auth_service.get_user_by_username(test_db, "finduser")

    assert result is not None
    assert result.username == "finduser"
    assert result.role == "teacher"


@pytest.mark.asyncio
async def test_get_user_by_username_not_found(test_db) -> None:
    """Test getting user by username when not found."""
    auth_service = AuthService()
    result = await auth_service.get_user_by_username(test_db, "nonexistent")

    assert result is None


def test_create_user_response() -> None:
    """Test creating user response object."""
    user = User(
        id=42,
        username="testuser",
        hashed_password="hash",
        display_name="Test User",
        avatar_url="/avatars/test.png",
        role="parent",
    )

    auth_service = AuthService()
    result = auth_service.create_user_response(user)

    assert result["id"] == 42
    assert result["username"] == "testuser"
    assert result["display_name"] == "Test User"
    assert result["avatar_url"] == "/avatars/test.png"
    assert result["role"] == "parent"
    assert "hashed_password" not in result  # Password should not be in response


def test_password_hashing() -> None:
    """Test that password hashing works correctly."""
    password = "testpassword123"
    hashed = get_password_hash(password)

    assert hashed != password
    assert verify_password(password, hashed) is True


def test_password_verification_with_wrong_password() -> None:
    """Test that password verification fails with wrong password."""
    password = "correctpassword"
    hashed = get_password_hash(password)

    assert verify_password("wrongpassword", hashed) is False
