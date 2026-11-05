CREATE TABLE reconciliation_run
(
    id          VARCHAR(36) PRIMARY KEY,
    channel     VARCHAR(100) NOT NULL,
    merchant    VARCHAR(100) NOT NULL,
    differences INT          NOT NULL,
    created_at  DATETIME(6) NOT NULL
);
CREATE TABLE reconciliation_difference
(
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    run_id       VARCHAR(36)  NOT NULL,
    business_key VARCHAR(200) NOT NULL,
    reason       VARCHAR(40)  NOT NULL,
    detail       TEXT         NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    INDEX        idx_reconciliation_open(status),
    INDEX        idx_reconciliation_run(run_id)
);
