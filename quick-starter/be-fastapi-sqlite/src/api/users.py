"""User management API endpoints."""

from fastapi import APIRouter, HTTPException, Response, status
from pydantic import BaseModel, Field

from src.api.dependencies import CurrentUserAdminDep, DatabaseDep
from src.services.auth import AuthService


class UserListResponse(BaseModel):
    """User list item response schema."""

    id: int
    username: str
    display_name: str
    role: str
    is_active: bool
    created_at: str


class CreateUserRequest(BaseModel):
    """Create user request schema."""

    username: str = Field(..., min_length=3, max_length=50)
    password: str = Field(..., min_length=8, max_length=100)
    display_name: str = Field(..., min_length=1, max_length=255)
    role: str = Field(..., pattern="^(admin|user)$")


class UpdateUserRequest(BaseModel):
    """Update user request schema."""

    display_name: str | None = Field(None, min_length=1, max_length=255)
    role: str | None = Field(None, pattern="^(admin|user)$")
    is_active: bool | None = None


router = APIRouter(prefix="/users", tags=["Users"])
auth_service = AuthService()


@router.get("", response_model=list[UserListResponse], status_code=status.HTTP_200_OK)
async def get_users(
    db: DatabaseDep,
    current_admin: CurrentUserAdminDep,
) -> list[UserListResponse]:
    """Get all users ordered by creation time (newest first).

    Args:
        db: Database session
        current_admin: Current admin user

    Returns:
        list[UserListResponse]: List of users
    """
    users = await auth_service.get_all_users(db)
    return [
        UserListResponse(
            id=user.id,
            username=user.username,
            display_name=user.display_name,
            role=user.role,
            is_active=user.is_active,
            created_at=user.created_at.isoformat() if user.created_at else "",
        )
        for user in users
    ]


@router.post("", response_model=UserListResponse, status_code=status.HTTP_201_CREATED)
async def create_user(
    request: CreateUserRequest,
    db: DatabaseDep,
    current_admin: CurrentUserAdminDep,
) -> UserListResponse:
    """Create a new user.

    Args:
        request: Create user request
        db: Database session
        current_admin: Current admin user

    Returns:
        UserListResponse: Created user

    Raises:
        HTTPException: If username already exists
    """
    # Check if username already exists
    existing = await auth_service.get_user_by_username(db, request.username)
    if existing:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail="Username already exists",
        )

    user = await auth_service.create_user(
        db,
        request.username,
        request.password,
        request.display_name,
        request.role,
    )

    return UserListResponse(
        id=user.id,
        username=user.username,
        display_name=user.display_name,
        role=user.role,
        is_active=user.is_active,
        created_at=user.created_at.isoformat() if user.created_at else "",
    )


@router.put("/{user_id}", response_model=UserListResponse, status_code=status.HTTP_200_OK)
async def update_user(
    user_id: int,
    request: UpdateUserRequest,
    db: DatabaseDep,
    current_admin: CurrentUserAdminDep,
) -> UserListResponse:
    """Update a user.

    Args:
        user_id: User ID to update
        request: Update user request
        db: Database session
        current_admin: Current admin user

    Returns:
        UserListResponse: Updated user

    Raises:
        HTTPException: If user not found or trying to demote self
    """
    # Prevent self-demotion
    if user_id == current_admin.id and request.role is not None and request.role != "admin":
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Cannot remove your own admin role",
        )

    user = await auth_service.update_user(
        db,
        user_id,
        display_name=request.display_name,
        role=request.role,
        is_active=request.is_active,
    )

    if not user:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found",
        )

    return UserListResponse(
        id=user.id,
        username=user.username,
        display_name=user.display_name,
        role=user.role,
        is_active=user.is_active,
        created_at=user.created_at.isoformat() if user.created_at else "",
    )


@router.delete("/{user_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_user(
    user_id: int,
    db: DatabaseDep,
    current_admin: CurrentUserAdminDep,
) -> Response:
    """Delete a user.

    Args:
        user_id: User ID to delete
        db: Database session
        current_admin: Current admin user

    Returns:
        Response: 204 No Content

    Raises:
        HTTPException: If user not found or trying to delete self
    """
    # Prevent self-deletion
    if user_id == current_admin.id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="Cannot delete your own account",
        )

    deleted = await auth_service.delete_user(db, user_id)

    if not deleted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail="User not found",
        )

    return Response(status_code=status.HTTP_204_NO_CONTENT)
