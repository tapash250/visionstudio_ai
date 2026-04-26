"""Create base tables

Revision ID: 001
Revises: 
Create Date: 2026-04-26

"""
from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

revision = '001'
down_revision = None
branch_labels = None
depends_on = None

def upgrade() -> None:
    op.create_table('users',
        sa.Column('id', sa.String(), nullable=False),
        sa.Column('email', sa.String(), nullable=False),
        sa.Column('email_verified', sa.DateTime(), nullable=True),
        sa.Column('password_hash', sa.String(), nullable=True),
        sa.Column('name', sa.String(), nullable=True),
        sa.Column('avatar', sa.String(), nullable=True),
        sa.Column('preferences', sa.JSON(), nullable=True),
        sa.Column('theme', sa.String(), default='dark'),
        sa.Column('language', sa.String(), default='en'),
        sa.Column('mature_enabled', sa.Boolean(), default=False),
        sa.Column('trust_score', sa.Integer(), default=100),
        sa.Column('banned_at', sa.DateTime(), nullable=True),
        sa.Column('ban_reason', sa.String(), nullable=True),
        sa.Column('created_at', sa.DateTime(), default=sa.func.now()),
        sa.Column('updated_at', sa.DateTime(), default=sa.func.now(), onupdate=sa.func.now()),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('email')
    )

    op.create_table('projects',
        sa.Column('id', sa.String(), nullable=False),
        sa.Column('user_id', sa.String(), nullable=False),
        sa.Column('title', sa.String(), nullable=False),
        sa.Column('description', sa.String(), nullable=True),
        sa.Column('thumbnail_url', sa.String(), nullable=True),
        sa.Column('source_url', sa.String(), nullable=True),
        sa.Column('result_url', sa.String(), nullable=True),
        sa.Column('type', sa.String(), default='GENERATION'),
        sa.Column('status', sa.String(), default='DRAFT'),
        sa.Column('is_public', sa.Boolean(), default=False),
        sa.Column('is_mature', sa.Boolean(), default=False),
        sa.Column('is_encrypted', sa.Boolean(), default=False),
        sa.Column('metadata', sa.JSON(), nullable=True),
        sa.Column('tags', sa.ARRAY(sa.String()), default=list),
        sa.Column('created_at', sa.DateTime(), default=sa.func.now()),
        sa.Column('updated_at', sa.DateTime(), default=sa.func.now(), onupdate=sa.func.now()),
        sa.PrimaryKeyConstraint('id'),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE')
    )

    op.create_table('generation_jobs',
        sa.Column('id', sa.String(), nullable=False),
        sa.Column('project_id', sa.String(), nullable=False),
        sa.Column('user_id', sa.String(), nullable=False),
        sa.Column('prompt', sa.Text(), nullable=False),
        sa.Column('negative_prompt', sa.Text(), nullable=True),
        sa.Column('enhanced_prompt', sa.Text(), nullable=True),
        sa.Column('model', sa.String(), default='flux-dev'),
        sa.Column('style', sa.String(), nullable=True),
        sa.Column('aspect_ratio', sa.String(), default='9:16'),
        sa.Column('seed', sa.Integer(), nullable=True),
        sa.Column('steps', sa.Integer(), default=30),
        sa.Column('cfg_scale', sa.Float(), default=7.5),
        sa.Column('batch_size', sa.Integer(), default=1),
        sa.Column('batch_index', sa.Integer(), default=0),
        sa.Column('result_urls', sa.ARRAY(sa.String()), default=list),
        sa.Column('selected_url', sa.String(), nullable=True),
        sa.Column('status', sa.String(), default='PENDING'),
        sa.Column('priority', sa.Integer(), default=5),
        sa.Column('worker_id', sa.String(), nullable=True),
        sa.Column('started_at', sa.DateTime(), nullable=True),
        sa.Column('completed_at', sa.DateTime(), nullable=True),
        sa.Column('error', sa.String(), nullable=True),
        sa.Column('compute_time', sa.Float(), nullable=True),
        sa.Column('gpu_type', sa.String(), nullable=True),
        sa.Column('created_at', sa.DateTime(), default=sa.func.now()),
        sa.PrimaryKeyConstraint('id'),
        sa.ForeignKeyConstraint(['project_id'], ['projects.id'], ondelete='CASCADE'),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.UniqueConstraint('project_id')
    )

    op.create_table('edit_jobs',
        sa.Column('id', sa.String(), nullable=False),
        sa.Column('project_id', sa.String(), nullable=False),
        sa.Column('user_id', sa.String(), nullable=False),
        sa.Column('source_url', sa.String(), nullable=False),
        sa.Column('operation', sa.String(), nullable=False),
        sa.Column('mask_url', sa.String(), nullable=True),
        sa.Column('prompt', sa.Text(), nullable=True),
        sa.Column('strength', sa.Float(), default=0.75),
        sa.Column('preserve_face', sa.Boolean(), default=True),
        sa.Column('result_url', sa.String(), nullable=True),
        sa.Column('before_after', sa.JSON(), nullable=True),
        sa.Column('status', sa.String(), default='PENDING'),
        sa.Column('priority', sa.Integer(), default=5),
        sa.Column('worker_id', sa.String(), nullable=True),
        sa.Column('started_at', sa.DateTime(), nullable=True),
        sa.Column('completed_at', sa.DateTime(), nullable=True),
        sa.Column('error', sa.String(), nullable=True),
        sa.Column('created_at', sa.DateTime(), default=sa.func.now()),
        sa.PrimaryKeyConstraint('id'),
        sa.ForeignKeyConstraint(['project_id'], ['projects.id'], ondelete='CASCADE'),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.UniqueConstraint('project_id')
    )

    op.create_table('animation_jobs',
        sa.Column('id', sa.String(), nullable=False),
        sa.Column('project_id', sa.String(), nullable=False),
        sa.Column('user_id', sa.String(), nullable=False),
        sa.Column('source_url', sa.String(), nullable=False),
        sa.Column('source_type', sa.String(), default='image'),
        sa.Column('animation_type', sa.String(), nullable=False),
        sa.Column('duration', sa.Integer(), default=3),
        sa.Column('fps', sa.Integer(), default=24),
        sa.Column('audio_url', sa.String(), nullable=True),
        sa.Column('result_url', sa.String(), nullable=True),
        sa.Column('preview_url', sa.String(), nullable=True),
        sa.Column('format', sa.String(), default='mp4'),
        sa.Column('status', sa.String(), default='PENDING'),
        sa.Column('priority', sa.Integer(), default=5),
        sa.Column('worker_id', sa.String(), nullable=True),
        sa.Column('started_at', sa.DateTime(), nullable=True),
        sa.Column('completed_at', sa.DateTime(), nullable=True),
        sa.Column('error', sa.String(), nullable=True),
        sa.Column('created_at', sa.DateTime(), default=sa.func.now()),
        sa.PrimaryKeyConstraint('id'),
        sa.ForeignKeyConstraint(['project_id'], ['projects.id'], ondelete='CASCADE'),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.UniqueConstraint('project_id')
    )

    op.create_table('style_presets',
        sa.Column('id', sa.String(), nullable=False),
        sa.Column('name', sa.String(), nullable=False),
        sa.Column('label', sa.String(), nullable=False),
        sa.Column('category', sa.String(), nullable=False),
        sa.Column('description', sa.String(), nullable=True),
        sa.Column('prompt_prefix', sa.Text(), nullable=True),
        sa.Column('prompt_suffix', sa.Text(), nullable=True),
        sa.Column('negative_prompt', sa.Text(), nullable=True),
        sa.Column('thumbnail_url', sa.String(), nullable=True),
        sa.Column('color_accent', sa.String(), nullable=True),
        sa.Column('model', sa.String(), default='flux-dev'),
        sa.Column('cfg_scale', sa.Float(), default=7.0),
        sa.Column('steps', sa.Integer(), default=30),
        sa.Column('is_mature', sa.Boolean(), default=False),
        sa.Column('use_count', sa.Integer(), default=0),
        sa.Column('created_at', sa.DateTime(), default=sa.func.now()),
        sa.PrimaryKeyConstraint('id'),
        sa.UniqueConstraint('name')
    )

    op.create_table('moderation_logs',
        sa.Column('id', sa.String(), nullable=False),
        sa.Column('user_id', sa.String(), nullable=False),
        sa.Column('content_type', sa.String(), nullable=False),
        sa.Column('content_url', sa.String(), nullable=True),
        sa.Column('prompt', sa.Text(), nullable=True),
        sa.Column('action', sa.String(), nullable=False),
        sa.Column('score', sa.Float(), nullable=True),
        sa.Column('categories', sa.ARRAY(sa.String()), default=list),
        sa.Column('reason', sa.String(), nullable=True),
        sa.Column('reviewed_by', sa.String(), nullable=True),
        sa.Column('reviewed_at', sa.DateTime(), nullable=True),
        sa.Column('appeal_status', sa.String(), nullable=True),
        sa.Column('created_at', sa.DateTime(), default=sa.func.now()),
        sa.PrimaryKeyConstraint('id'),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE')
    )

    op.create_table('mature_consents',
        sa.Column('id', sa.String(), nullable=False),
        sa.Column('user_id', sa.String(), nullable=False),
        sa.Column('consent_given', sa.Boolean(), nullable=False),
        sa.Column('consent_date', sa.DateTime(), nullable=False),
        sa.Column('verified_method', sa.String(), nullable=False),
        sa.Column('ip_address', sa.String(), nullable=True),
        sa.Column('session_timeout', sa.Integer(), default=30),
        sa.Column('last_accessed', sa.DateTime(), nullable=False),
        sa.PrimaryKeyConstraint('id'),
        sa.ForeignKeyConstraint(['user_id'], ['users.id'], ondelete='CASCADE'),
        sa.UniqueConstraint('user_id')
    )

    # Indexes
    op.create_index('ix_projects_user_created', 'projects', ['user_id', 'created_at'])
    op.create_index('ix_generation_jobs_status_priority', 'generation_jobs', ['status', 'priority'])
    op.create_index('ix_style_presets_category', 'style_presets', ['category'])
    op.create_index('ix_moderation_logs_action_created', 'moderation_logs', ['action', 'created_at'])

def downgrade() -> None:
    op.drop_table('mature_consents')
    op.drop_table('moderation_logs')
    op.drop_table('style_presets')
    op.drop_table('animation_jobs')
    op.drop_table('edit_jobs')
    op.drop_table('generation_jobs')
    op.drop_table('projects')
    op.drop_table('users')
