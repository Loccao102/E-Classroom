ALTER TABLE communication.announcements
  ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS pinned BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_announcement_audience_time
  ON communication.announcements(school_id, target_type, target_id, published_at DESC, id DESC);

UPDATE communication.teacher_comments
SET visibility='STUDENT_AND_GUARDIAN'
WHERE visibility='GUARDIAN_AND_STUDENT';

ALTER TABLE communication.teacher_comments
  ALTER COLUMN visibility SET DEFAULT 'STUDENT_AND_GUARDIAN';

ALTER TABLE communication.conversation_participants
  ADD COLUMN IF NOT EXISTS last_read_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS muted BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE communication.conversations
  ADD COLUMN IF NOT EXISTS last_message_at TIMESTAMPTZ;

UPDATE communication.conversations c
SET last_message_at=(
  SELECT MAX(m.created_at)
  FROM communication.messages m
  WHERE m.conversation_id=c.id
)
WHERE last_message_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_conversation_participant_user
  ON communication.conversation_participants(user_id, conversation_id);

CREATE INDEX IF NOT EXISTS idx_conversation_school_activity
  ON communication.conversations(school_id, last_message_at DESC NULLS LAST, created_at DESC, id DESC);

ALTER TABLE notification.notifications
  ADD COLUMN IF NOT EXISTS category VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
  ADD COLUMN IF NOT EXISTS delivery_status VARCHAR(32) NOT NULL DEFAULT 'DELIVERED';

CREATE INDEX IF NOT EXISTS idx_notification_unread_feed
  ON notification.notifications(recipient_user_id, school_id, category, created_at DESC, id DESC)
  WHERE read_at IS NULL;

CREATE TABLE IF NOT EXISTS notification.preferences (
  school_id UUID NOT NULL REFERENCES school.schools(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES identity.users(id) ON DELETE CASCADE,
  category VARCHAR(32) NOT NULL,
  in_app_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  realtime_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY(school_id, user_id, category)
);

CREATE INDEX IF NOT EXISTS idx_notification_preferences_user
  ON notification.preferences(user_id, school_id, category);
