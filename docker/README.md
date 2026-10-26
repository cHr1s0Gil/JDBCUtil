# JDBCUtil local databases

The root Compose file starts PostgreSQL and Oracle together.

## Start both databases

```powershell
cd docker
docker compose up -d
docker compose ps
```

## Connection settings

| Database | Host port | Database or service | User | Test table |
| --- | ---: | --- | --- | --- |
| PostgreSQL | `15432` | `postgre` | `postgre_user` | `jdbcutil_postgres_benchmark` |
| Oracle | `1521` | `XEPDB1` | `jdbcutil_user` | `JDBCUTIL_ORACLE_BENCHMARK` |

Passwords are stored in the ignored `.env` file.

## Verify

```powershell
docker exec jdbcutil-postgres psql -U postgre_user -d postgre -c "SELECT * FROM jdbcutil_postgres_benchmark;"
docker exec jdbcutil-oracle bash -lc "echo 'SELECT * FROM JDBCUTIL_ORACLE_BENCHMARK;' | sqlplus -s 'jdbcutil_user/<password>@//localhost:1521/XEPDB1'"
```

## SCC mappings

Create separate TCP mappings.

| Database | Internal port | Suggested virtual host | Virtual port |
| --- | ---: | --- | ---: |
| PostgreSQL | `15432` | `jdbcutil-postgres.internal` | `5432` |
| Oracle | `1521` | `jdbcutil-oracle.internal` | `1521` |

The internal host is the LAN IP of the Docker host. Pass the SCC virtual host and virtual port to JDBCUtil.

## Stop

```powershell
docker compose down
```

Named volumes are retained. Use `docker compose down -v` only when the database data may be deleted.

