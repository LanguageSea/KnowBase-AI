-- Stage 4 observability query indexes.
-- Existing V1 tables are reused; no destructive schema changes are made.

CREATE INDEX idx_oplog_action_time
    ON sys_operation_log (action, created_at);

CREATE INDEX idx_oplog_result_time
    ON sys_operation_log (result, created_at);

CREATE INDEX idx_task_error_time
    ON kb_ingestion_task (error_code, created_at);
