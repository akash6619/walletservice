INSERT INTO users (user_id, name)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'Demo User A'),
    ('00000000-0000-0000-0000-000000000002', 'Demo User B')
ON CONFLICT (user_id) DO UPDATE
SET name = EXCLUDED.name,
    active = TRUE;

