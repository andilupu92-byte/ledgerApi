#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
./mvnw -B verify
docker compose up --build -d
echo "Ledger: http://localhost:8080"
echo "RabbitMQ UI: http://localhost:15672 (guest/guest)"
echo "Sample payment-settled: POST http://localhost:8080/v1/debug/payment-settled"
