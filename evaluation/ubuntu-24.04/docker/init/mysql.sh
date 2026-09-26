#!/usr/bin/env bash
set -euo pipefail
mysql --protocol=socket -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE DATABASE IF NOT EXISTS dcg_history;
CREATE DATABASE IF NOT EXISTS iems_app;
GRANT ALL PRIVILEGES ON dcg_history.* TO '${MYSQL_USER}'@'%';
GRANT ALL PRIVILEGES ON iems_app.* TO '${MYSQL_USER}'@'%';
FLUSH PRIVILEGES;
SQL
