-- Make transaction_id nullable
ALTER TABLE settled_payment
MODIFY COLUMN transaction_id CHAR(36) NULL;

-- Insert settlement account only if it does not exist
INSERT INTO account (
    id, currency, overdraft_limit, unlimited_overdraft, balance, created_at
)
SELECT
    'settlement-usd', 'USD', 0, TRUE, 0, NOW(6)
WHERE NOT EXISTS (
    SELECT 1
    FROM account
    WHERE id = 'settlement-usd'
);