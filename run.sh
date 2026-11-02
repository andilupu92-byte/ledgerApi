#!/usr/bin/env bash
set -euo pipefail
./mvnw -B verify
docker compose up --build -d
echo "Ledger: http://localhost:8080  |  RabbitMQ UI: http://localhost:15672 (guest/guest)"