"""Integration tests for complete login flow."""
import pytest
from httpx import AsyncClient


@pytest.mark.asyncio
async def test_complete_login_flow(async_client: AsyncClient) -> None:
    """Test the complete login flow from authentication to accessing protected endpoint."""
    # Step 1: Login with valid credentials
    login_response = await async_client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "password123"},
    )

    assert login_response.status_code == 200
    login_data = login_response.json()
    assert "access_token" in login_data

    token = login_data["access_token"]

    # Step 2: Access protected endpoint with token
    me_response = await async_client.get(
        "/api/auth/me",
        headers={"Authorization": f"Bearer {token}"},
    )

    assert me_response.status_code == 200
    me_data = me_response.json()
    assert me_data["username"] == "admin"
    assert me_data["display_name"] == "Administrator"
    assert me_data["avatar_url"] == "/avatars/admin.png"


@pytest.mark.asyncio
async def test_login_flow_with_invalid_credentials(async_client: AsyncClient) -> None:
    """Test login flow fails with invalid credentials."""
    # Try to login with invalid credentials
    login_response = await async_client.post(
        "/api/auth/login",
        json={"username": "admin", "password": "wrongpassword"},
    )

    assert login_response.status_code == 401

    # Verify no token was issued
    login_data = login_response.json()
    assert "access_token" not in login_data


