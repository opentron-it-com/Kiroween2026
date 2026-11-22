-- Align H2 text columns with the JPA String mapping while preserving existing data.
ALTER TABLE agent_memory ALTER COLUMN raw_trace SET DATA TYPE VARCHAR;
ALTER TABLE agent_memory ALTER COLUMN compressed_summary SET DATA TYPE VARCHAR;

ALTER TABLE trace_logs ALTER COLUMN input SET DATA TYPE VARCHAR;
ALTER TABLE trace_logs ALTER COLUMN output SET DATA TYPE VARCHAR;
ALTER TABLE trace_logs ALTER COLUMN tools_used SET DATA TYPE VARCHAR;
ALTER TABLE trace_logs ALTER COLUMN routing_reason SET DATA TYPE VARCHAR;

ALTER TABLE generated_projects ALTER COLUMN description SET DATA TYPE VARCHAR;
ALTER TABLE generated_projects ALTER COLUMN file_list SET DATA TYPE VARCHAR;
ALTER TABLE generated_projects ALTER COLUMN files_json SET DATA TYPE VARCHAR;

ALTER TABLE oauth_tokens ALTER COLUMN access_token SET DATA TYPE VARCHAR;
ALTER TABLE oauth_tokens ALTER COLUMN refresh_token SET DATA TYPE VARCHAR;

ALTER TABLE trace_logs ALTER COLUMN cost_usd SET DATA TYPE DOUBLE PRECISION;
