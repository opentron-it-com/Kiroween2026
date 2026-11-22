-- V004__add_token_cost_tracking.sql
-- Migration to add token tracking and cost accounting columns to trace_logs table

ALTER TABLE trace_logs
ADD COLUMN IF NOT EXISTS model_name VARCHAR(255),
ADD COLUMN IF NOT EXISTS model_provider VARCHAR(100),
ADD COLUMN IF NOT EXISTS input_tokens INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS output_tokens INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS cache_creation_tokens INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS cache_read_tokens INTEGER DEFAULT 0,
ADD COLUMN IF NOT EXISTS cost_usd DECIMAL(10,6) DEFAULT 0.0,
ADD COLUMN IF NOT EXISTS classification VARCHAR(50),
ADD COLUMN IF NOT EXISTS routing_reason TEXT;

-- Create index for cost analysis queries
CREATE INDEX IF NOT EXISTS idx_trace_logs_model_provider ON trace_logs(model_provider);
CREATE INDEX IF NOT EXISTS idx_trace_logs_classification ON trace_logs(classification);
CREATE INDEX IF NOT EXISTS idx_trace_logs_cost ON trace_logs(cost_usd);
CREATE INDEX IF NOT EXISTS idx_trace_logs_timestamp_cost ON trace_logs(timestamp, cost_usd);
