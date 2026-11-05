-- Apply once to order database before deploying recovery workers.
ALTER TABLE order_saga
    ADD COLUMN lease_token VARCHAR(36) NULL, ADD COLUMN lease_until DATETIME(6) NULL;
CREATE INDEX idx_saga_recovery ON order_saga (next_retry_time, status);
