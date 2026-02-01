"""seed_preset_users

Revision ID: 726ef114da2e
Revises: bb5d29c0d5f3
Create Date: 2026-01-21 20:31:17.558841

"""
from typing import Sequence, Union
from datetime import datetime, timezone

from alembic import op
import sqlalchemy as sa


# revision identifiers, used by Alembic.
revision: str = '726ef114da2e'
down_revision: Union[str, Sequence[str], None] = 'bb5d29c0d5f3'
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    """Upgrade schema."""
    # Pre-hashed password for "password123" using bcrypt (cost 12)
    default_password = "$2b$12$xh.HRKOsYz8BvgrZFcgJCuTZF9khFIZo5CAfmKNS6h1ossgoCQdQq"
    now = datetime.now(timezone.utc)

    op.bulk_insert(
        sa.table(
            'users',
            sa.column('id', sa.Integer),
            sa.column('username', sa.String),
            sa.column('hashed_password', sa.String),
            sa.column('display_name', sa.String),
            sa.column('avatar_url', sa.String),
            sa.column('role', sa.String),
            sa.column('is_active', sa.Boolean),
            sa.column('created_at', sa.DateTime),
            sa.column('updated_at', sa.DateTime),
        ),
        [
            {
                'id': 1,
                'username': 'admin',
                'hashed_password': default_password,
                'display_name': 'Administrator',
                'avatar_url': '/avatars/admin.png',
                'role': 'admin',
                'is_active': True,
                'created_at': now,
                'updated_at': now,
            },
        ]
    )


def downgrade() -> None:
    """Downgrade schema."""
    op.execute("DELETE FROM users WHERE username IN ('parent1', 'teacher1', 'student1', 'admin')")
