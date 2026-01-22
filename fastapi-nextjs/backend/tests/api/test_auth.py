"""Tests for authentication API endpoints."""
import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
class TestLoginEndpoint:
    """Tests for POST /api/auth/login endpoint."""

    async def test_login_success_returns_200_with_token(
        self, async_client: AsyncClient
    ) -> None:
        """Test that successful login returns 200 with access token."""
        response = await async_client.post(
            "/api/auth/login",
            json={"username": "admin", "password": "password123"},
        )

        assert response.status_code == 200
        data = response.json()
        assert "access_token" in data

    async def test_login_invalid_credentials_returns_401(
        self, async_client: AsyncClient
    ) -> None:
        """Test that invalid credentials return 401."""
        response = await async_client.post(
            "/api/auth/login",
            json={"username": "admin", "password": "wrongpassword"},
        )

        assert response.status_code == 401
        data = response.json()
        assert "detail" in data

    async def test_login_nonexistent_user_returns_401(
        self, async_client: AsyncClient
    ) -> None:
        """Test that nonexistent user returns 401."""
        response = await async_client.post(
            "/api/auth/login",
            json={"username": "nonexistent", "password": "password123"},
        )

        assert response.status_code == 401

    async def test_login_missing_fields_returns_422(
        self, async_client: AsyncClient
    ) -> None:
        """Test that missing required fields returns 422."""
        response = await async_client.post(
            "/api/auth/login",
            json={"username": "admin"},  # Missing password
        )

        assert response.status_code == 422


@pytest.mark.asyncio
class TestGetCurrentUserEndpoint:
    """Tests for GET /api/auth/me endpoint."""

    async def test_get_current_user_with_valid_token_returns_200(
        self, async_client: AsyncClient
    ) -> None:
        """Test that valid token returns 200 with user info."""
        # First login to get token
        login_response = await async_client.post(
            "/api/auth/login",
            json={"username": "admin", "password": "password123"},
        )
        token = login_response.json()["access_token"]

        # Get current user
        response = await async_client.get(
            "/api/auth/me",
            headers={"Authorization": f"Bearer {token}"},
        )

        assert response.status_code == 200
        data = response.json()
        assert data["username"] == "admin"
        assert data["display_name"] == "Administrator"
        assert data["avatar_url"] == "/avatars/admin.png"

    async def test_get_current_user_without_token_returns_401(
        self, async_client: AsyncClient
    ) -> None:
        """Test that missing token returns 401."""
        response = await async_client.get("/api/auth/me")

        assert response.status_code == 401

    async def test_get_current_user_with_invalid_token_returns_401(
        self, async_client: AsyncClient
    ) -> None:
        """Test that invalid token returns 401."""
        response = await async_client.get(
            "/api/auth/me",
            headers={"Authorization": "Bearer invalid_token"},
        )

        assert response.status_code == 401
