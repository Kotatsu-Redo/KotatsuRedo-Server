-- The last ranking of each chart window, kept so that a server which has just started can answer from it
-- at once. Rankings used to live only in memory, so after every restart the first requests had to build
-- one while the database was at its busiest, and could time out with nothing to fall back on.
--
-- One row per window, replaced by each background refresh. The ranking is stored as the JSON text the
-- server serves, so reading it back needs no query over ratings or comments at all. Created by the
-- migration role like every other table, so the API's role receives its data access through the default
-- privileges set in deploy/postgres-init.sh and nothing here has to grant it.
CREATE TABLE chart_ranking (
    window_name  TEXT        PRIMARY KEY,
    generated_at TIMESTAMPTZ NOT NULL,
    ranking      TEXT        NOT NULL
);
