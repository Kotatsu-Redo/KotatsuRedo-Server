#!/bin/sh
set -eu

# Runs only when the PostgreSQL data directory is first created. Flyway connects as POSTGRES_USER;
# its future objects inherit these default grants, while the API role receives data access but no
# schema ownership, role management, or database-superuser powers.
psql --set=ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=runtime_user="$DATABASE_USER" --set=runtime_password="$DATABASE_PASSWORD" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN PASSWORD %L', :'runtime_user', :'runtime_password')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = :'runtime_user') \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'runtime_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'runtime_user') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO %I', :'runtime_user') \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO %I', :'runtime_user') \gexec
SELECT format(
  'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
  current_user, :'runtime_user'
) \gexec
SELECT format(
  'ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO %I',
  current_user, :'runtime_user'
) \gexec
SQL
