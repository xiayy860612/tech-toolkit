"""Authentication API endpoints."""

from fastapi import APIRouter, HTTPException, Response, status
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field

from src.api.dependencies import CurrentUserDep, DatabaseDep
from src.core.config import settings
from src.core.security import create_access_token
from src.services.auth import AuthService


class LoginRequest(BaseModel):
    """Login request schema."""

    username: str = Field(..., min_length=3, max_length=50)
    password: str = Field(..., min_length=8)


class LoginResponse(BaseModel):
    """Login response schema."""

    access_token: str


class UserResponse(BaseModel):
    """User response schema."""

    id: int
    username: str
    display_name: str
    avatar_url: str | None


router = APIRouter(prefix="/auth", tags=["Authentication"])
auth_service = AuthService()


@router.post("/login", status_code=status.HTTP_200_OK)
async def login(
    request: LoginRequest,
    response: Response,
    db: DatabaseDep,
) -> LoginResponse:
    """Authenticate user with username and password.

    Args:
        request: Login request with username and password
        response: FastAPI Response object for setting cookies
        db: Database session

    Returns:
        LoginResponse: Access token

    Raises:
        HTTPException: If credentials are invalid
    """
    user = await auth_service.authenticate_user(db, request.username, request.password)

    if not user:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid username or password",
        )

    access_token = create_access_token(
        data={"sub": str(user.id), "username": user.username, "role": user.role}
    )

    return LoginResponse(access_token=access_token)


@router.get("/me", response_model=UserResponse)
async def get_current_user_info(
    current_user: CurrentUserDep,
) -> UserResponse:
    """Get current authenticated user information.

    Args:
        current_user: Current authenticated user

    Returns:
        UserResponse: User information
    """
    return UserResponse(
        id=current_user.id,
        username=current_user.username,
        display_name=current_user.display_name,
        avatar_url=current_user.avatar_url,
    )


@router.post("/logout")
async def logout(
    response: Response,
    current_user: CurrentUserDep,
) -> JSONResponse:
    """Logout the current user by clearing the httpOnly cookie.

    Args:
        response: FastAPI Response object for clearing cookies
        current_user: Current authenticated user

    Returns:
        JSONResponse: Success message
    """
    # Clear the httpOnly cookie by setting it with expired date
    response.delete_cookie(key="access_token")

    return JSONResponse(content={"message": "Successfully logged out"})
