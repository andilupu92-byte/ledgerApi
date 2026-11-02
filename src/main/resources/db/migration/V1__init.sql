CREATE TABLE account (
  id                  VARCHAR(64) NOT NULL PRIMARY KEY,
  currency            CHAR(3)     NOT NULL,
  overdraft_limit     BIGINT      NOT NULL DEFAULT 0,
  unlimited_overdraft BOOLEAN     NOT NULL DEFAULT FALSE,
  balance             BIGINT      NOT NULL DEFAULT 0,
  created_at          DATETIME(6) NOT NULL,
  CONSTRAINT chk_overdraft CHECK (unlimited_overdraft OR balance + overdraft_limit >= 0),
  CONSTRAINT chk_limit_pos CHECK (overdraft_limit >= 0)
) ENGINE=InnoDB;

CREATE TABLE ledger_transaction (
  id                      CHAR(36)     NOT NULL PRIMARY KEY,
  description             VARCHAR(255) NOT NULL,
  recorded_at             DATETIME(6)  NOT NULL,
  reverses_transaction_id CHAR(36)     NULL,
  UNIQUE KEY uq_reverses (reverses_transaction_id),
  CONSTRAINT fk_reverses FOREIGN KEY (reverses_transaction_id) REFERENCES ledger_transaction(id)
) ENGINE=InnoDB;

CREATE TABLE entry (
  id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  transaction_id CHAR(36)    NOT NULL,
  account_id     VARCHAR(64) NOT NULL,
  direction      ENUM('DEBIT','CREDIT') NOT NULL,
  amount         BIGINT      NOT NULL,
  balance_after  BIGINT      NOT NULL,
  CONSTRAINT chk_amount CHECK (amount > 0),
  CONSTRAINT fk_entry_tx  FOREIGN KEY (transaction_id) REFERENCES ledger_transaction(id),
  CONSTRAINT fk_entry_acc FOREIGN KEY (account_id) REFERENCES account(id),
  KEY idx_entry_account (account_id, id)
) ENGINE=InnoDB;

CREATE TABLE idempotency_key (
  idem_key        VARCHAR(128) NOT NULL,
  operation       VARCHAR(64)  NOT NULL,
  request_hash    CHAR(64)     NOT NULL,
  response_status INT          NOT NULL,
  response_body   JSON         NOT NULL,
  created_at      DATETIME(6)  NOT NULL,
  PRIMARY KEY (idem_key)
) ENGINE=InnoDB;

CREATE TABLE settled_payment (
  payment_id     VARCHAR(128) NOT NULL PRIMARY KEY,
  event_id       VARCHAR(128) NOT NULL,
  request_hash   CHAR(64)     NOT NULL,
  transaction_id CHAR(36)     NOT NULL,
  created_at     DATETIME(6)  NOT NULL,
  CONSTRAINT fk_sp_tx FOREIGN KEY (transaction_id) REFERENCES ledger_transaction(id)
) ENGINE=InnoDB;

CREATE TABLE outbox (
  id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
  transaction_id CHAR(36)    NOT NULL,
  request_id     VARCHAR(64) NULL,
  payload        JSON        NOT NULL,
  created_at     DATETIME(6) NOT NULL,
  published_at   DATETIME(6) NULL,
  KEY idx_outbox_unpublished (published_at, id)
) ENGINE=InnoDB;

INSERT INTO account (id, currency, overdraft_limit, unlimited_overdraft, balance, created_at) VALUES
  ('settlement-eur', 'EUR', 0, TRUE, 0, NOW(6)),
  ('settlement-jpy', 'JPY', 0, TRUE, 0, NOW(6));