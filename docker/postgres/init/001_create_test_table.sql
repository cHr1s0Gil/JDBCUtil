CREATE TABLE IF NOT EXISTS jdbcutil_postgres_benchmark (
    record_key INTEGER PRIMARY KEY,
    record_value INTEGER NOT NULL
);

INSERT INTO jdbcutil_postgres_benchmark (record_key, record_value)
VALUES (1, 100)
ON CONFLICT (record_key)
DO UPDATE SET record_value = EXCLUDED.record_value;

