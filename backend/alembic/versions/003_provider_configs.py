"""Add encrypted per-user provider configurations.

Revision ID: 003
Revises: 002
"""
import sqlalchemy as sa
from alembic import op

revision = "003"
down_revision = "002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "provider_configs",
        sa.Column("id", sa.String(), primary_key=True),
        sa.Column("user_id", sa.String(), sa.ForeignKey("users.id", ondelete="CASCADE"), nullable=False),
        sa.Column("provider_id", sa.String(128), nullable=False),
        sa.Column("model_id", sa.String(256), nullable=False),
        sa.Column("base_url", sa.String(2048), nullable=False),
        sa.Column("encrypted_api_key", sa.Text(), nullable=False),
        sa.Column("is_active", sa.Boolean(), nullable=False, server_default=sa.false()),
        sa.Column("created_at", sa.DateTime(), nullable=False, server_default=sa.func.now()),
        sa.Column("updated_at", sa.DateTime(), nullable=False, server_default=sa.func.now()),
        sa.UniqueConstraint("user_id", "provider_id", name="uq_provider_config_user_provider"),
    )
    op.create_index("ix_provider_configs_user_id", "provider_configs", ["user_id"])


def downgrade() -> None:
    op.drop_index("ix_provider_configs_user_id", table_name="provider_configs")
    op.drop_table("provider_configs")
