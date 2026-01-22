"""Application configuration using Pydantic Settings."""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Application settings.

    Attributes:
        DATABASE_URL: Database connection URL
        SECRET_KEY: Secret key for JWT signing
        ALGORITHM: JWT algorithm
        ACCESS_TOKEN_EXPIRE_MINUTES: Token expiration time in minutes
        FRONTEND_URL: Frontend URL for CORS
        COOKIE_SECURE: Whether cookies should be set with Secure flag
    """

    DATABASE_URL: str = "sqlite+aiosqlite:///./homework_review.db"
    SECRET_KEY: str = "change-this-secret-key-in-production"
    ALGORITHM: str = "HS256"
    ACCESS_TOKEN_EXPIRE_MINUTES: int = 1440  # 24 hours
    FRONTEND_URL: str = "http://localhost:3000"
    COOKIE_SECURE: bool = False  # Set to True in production with HTTPS

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )


settings = Settings()
