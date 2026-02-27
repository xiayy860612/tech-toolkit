"""Authentication service."""

from typing import Any

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from src.core.security import get_password_hash, verify_password
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
            "is_active": user.is_active,
            "created_at": user.created_at.isoformat() if user.created_at else None,
        }

    async def get_all_users(self, db: AsyncSession) -> list[User]:
        """Get all users ordered by creation time (newest first).

        Args:
            db: Database session

        Returns:
            list[User]: List of all users
        """
        stmt = select(User).order_by(User.created_at.desc())
        result = await db.execute(stmt)
        return list(result.scalars().all())

    async def get_user_by_id(self, db: AsyncSession, user_id: int) -> User | None:
        """Retrieve user by ID.

        Args:
            db: Database session
            user_id: User ID to look up

        Returns:
            User | None: User object or None if not found
        """
        stmt = select(User).where(User.id == user_id)
        result = await db.execute(stmt)
        return result.scalar_one_or_none()

    async def create_user(
        self,
        db: AsyncSession,
        username: str,
        password: str,
        display_name: str,
        role: str,
    ) -> User:
        """Create a new user.

        Args:
            db: Database session
            username: Username
            password: Plain text password
            display_name: Display name
            role: User role (admin or user)

        Returns:
            User: Created user object
        """
        hashed_password = get_password_hash(password)
        user = User(
            username=username,
            hashed_password=hashed_password,
            display_name=display_name,
            role=role,
        )
        db.add(user)
        await db.commit()
        await db.refresh(user)
        return user

    async def update_user(
        self,
        db: AsyncSession,
        user_id: int,
        display_name: str | None = None,
        role: str | None = None,
        is_active: bool | None = None,
    ) -> User | None:
        """Update user fields.

        Args:
            db: Database session
            user_id: User ID to update
            display_name: New display name
            role: New role
            is_active: New active status

        Returns:
            User | None: Updated user object or None if not found
        """
        user = await self.get_user_by_id(db, user_id)
        if not user:
            return None

        if display_name is not None:
            user.display_name = display_name
        if role is not None:
            user.role = role
        if is_active is not None:
            user.is_active = is_active

        await db.commit()
        await db.refresh(user)
        return user

    async def delete_user(self, db: AsyncSession, user_id: int) -> bool:
        """Delete a user.

        Args:
            db: Database session
            user_id: User ID to delete

        Returns:
            bool: True if deleted, False if not found
        """
        user = await self.get_user_by_id(db, user_id)
        if not user:
            return False

        await db.delete(user)
        await db.commit()
        return True

    async def change_password(
        self,
        db: AsyncSession,
        user_id: int,
        current_password: str,
        new_password: str,
    ) -> bool:
        """Change user's password.

        Args:
            db: Database session
            user_id: User ID
            current_password: Current password for verification
            new_password: New password to set

        Returns:
            bool: True if successful, False if current password is incorrect
        """
        user = await self.get_user_by_id(db, user_id)
        if not user:
            return False

        if not verify_password(current_password, user.hashed_password):
            return False

        user.hashed_password = get_password_hash(new_password)
        await db.commit()
        return True
