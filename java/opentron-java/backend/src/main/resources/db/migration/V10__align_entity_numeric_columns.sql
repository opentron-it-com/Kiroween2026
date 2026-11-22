-- Align the Java Double cost field with a portable floating-point database type.
ALTER TABLE trace_logs
    ALTER COLUMN cost_usd TYPE DOUBLE PRECISION;