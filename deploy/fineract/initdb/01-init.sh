#!/bin/bash
# Creates the two databases Fineract expects on first boot of the cell's
# dedicated Postgres: the tenant STORE (fineract_tenants) and the default
# tenant's data DB (fineract_default). Runs only on an empty data volume.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<-EOSQL
    CREATE DATABASE fineract_tenants;
    CREATE DATABASE fineract_default;
EOSQL
