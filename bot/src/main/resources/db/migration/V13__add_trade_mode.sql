-- Add trade mode column to bot_configs (SWING or SCALP)
ALTER TABLE bot_configs ADD COLUMN trade_mode VARCHAR(10) NOT NULL DEFAULT 'SWING';
