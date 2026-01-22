"""Authentication service."""

from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from src.core.security import verify_password
from src.models.user import User


class AuthService:
    """Service for authentication operations."""

    async def get_user_by_username(
        self, db: AsyncSession, username: str
    ) -> User | None:
        """Retrieve user by username.

        Args:
            db: Database session
            username: Username to look up

        Returns:
            User | None: User object or None if not found
        """
        stmt = select(User).where(User.username == username)
        result = await db.execute(stmt)
        return result.scalar_one_or_none()

    async def authenticate_user(
        self,
        db: AsyncSession,
        username: str,
        password: str,
    ) -> User | None:
        """Authenticate user with username and password.

        Args:
            db: Database session
            username: Username
            password: Plain text password

        Returns:
            User | None: User object if authenticated, None otherwise
        """
        user = await self.get_user_by_username(db, username)
        if not user or not user.is_active:
            return None
        if not verify_password(password, user.hashed_password):
            return None
        return user

    def create_user_response(self, user: User) -> dict[str, Any]:
        """Create a user response object.

        Args:
            user: User model instance

        Returns:
            dict: User response data
        """
        return {
            "id": user.id,
            "username": user.username,
            "display_name": user.display_name,
            "avatar_url": user.avatar_url,
            "role": user.role,
        }
