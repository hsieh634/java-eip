#!/bin/sh
# 在同一個 PostgreSQL 容器內建立整合測試用資料庫。只在資料卷第一次初始化時執行。
set -e
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<-EOSQL
    CREATE DATABASE "${DB_TEST_NAME}" OWNER "${POSTGRES_USER}";
EOSQL
