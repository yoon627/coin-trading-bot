ALTER TABLE users ADD COLUMN admin BOOLEAN NOT NULL DEFAULT FALSE;

-- Set existing user 'yoon' as admin
UPDATE users SET admin = TRUE WHERE username = 'yoon';
