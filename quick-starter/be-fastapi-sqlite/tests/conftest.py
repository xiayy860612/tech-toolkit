"""Pytest configuration and fixtures."""
import asyncio
from collections.abc import AsyncGenerator
from datetime import datetime, timezone
from pathlib import Path
from typing import Generator

import pytest
import pytest_asyncio
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from src.api.dependencies import get_db
from src.core.database import Base, AsyncSessionLocal
from src.core.security import get_password_hash
from src.main import app
from src.models.user import User


# Test database file
TEST_DB_FILE = Path(__file__).parent.parent / "test_homework_review.db"
TEST_DATABASE_URL = f"sqlite+aiosqlite:///{TEST_DB_FILE}"


# Create test engine
test_engine = create_async_engine(TEST_DATABASE_URL, echo=False)
TestSessionLocal = async_sessionmaker(
    test_engine, class_=AsyncSession, expire_on_commit=False
)


@pytest.fixture(scope="session", autouse=True)
def cleanup_test_db() -> Generator[None, None, None]:
    """Cleanup test database before and after tests.

    Yields:
        None
    """
    # Cleanup before tests
    if TEST_DB_FILE.exists():
        TEST_DB_FILE.unlink()

    yield

    # Cleanup after tests
    if TEST_DB_FILE.exists():
        TEST_DB_FILE.unlink()


@pytest.fixture(scope="session")
def event_loop() -> Generator[asyncio.AbstractEventLoop, None, None]:
    """Create an instance of the default event loop for the test session.

    Yields:
        asyncio.AbstractEventLoop: Event loop
    """
    loop = asyncio.get_event_loop_policy().new_event_loop()
    yield loop
    loop.close()


async def _seed_test_users(session: AsyncSession) -> None:
    """Seed preset users for testing."""
    hashed_password = get_password_hash("password123")
    now = datetime.now(timezone.utc)

    preset_users = [
        User(
            id=1,
            username="admin",
            hashed_password=hashed_password,
            display_name="Administrator",
            avatar_url="/avatars/admin.png",
            role="admin",
            is_active=True,
            created_at=now,
            updated_at=now,
        ),
    ]

    for user in preset_users:
        session.add(user)
    await session.commit()


async def _get_test_db() -> AsyncGenerator[AsyncSession, None]:
    """Override get_db dependency for tests."""
    async with TestSessionLocal() as session:
        yield session


@pytest_asyncio.fixture
async def test_db() -> AsyncGenerator[AsyncSession, None]:
    """Create a test database session.

    Yields:
        AsyncSession: Test database session
    """
    # Create test tables
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    # Seed preset users
    async with TestSessionLocal() as session:
        await _seed_test_users(session)

    # Yield session for direct use in tests
    async with TestSessionLocal() as session:
        yield session

    # Cleanup
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)


@pytest_asyncio.fixture
async def async_client() -> AsyncGenerator[AsyncClient, None]:
    """Create an async test client for the FastAPI app.

    Yields:
        AsyncClient: Test client
    """
    # Create test tables
    async with test_engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)

    # Seed preset users
    async with TestSessionLocal() as session:
        await _seed_test_users(session)

    # Override the database dependency
    app.dependency_overrides[get_db] = _get_test_db

    try:
        async with AsyncClient(
            transport=ASGITransport(app=app), base_url="http://test"
        ) as client:
            yield client
    finally:
        # Cleanup dependency override
        app.dependency_overrides.clear()

        # Cleanup tables
        async with test_engine.begin() as conn:
            await conn.run_sync(Base.metadata.drop_all)
