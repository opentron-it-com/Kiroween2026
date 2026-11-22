#!/bin/bash
# Start PostgreSQL in Docker (if not running)

# Check if Docker is running
if ! command -v docker &> /dev/null; then
    echo "Docker not found. Install Docker Desktop."
    exit 1
fi

# Check if postgres container exists
if docker ps -a --format '{{.Names}}' | grep -q '^opentron-postgres$'; then
    echo "Starting existing PostgreSQL container..."
    docker start opentron-postgres
else
    echo "Creating new PostgreSQL container..."
    docker run -d \
        --name opentron-postgres \
        -e POSTGRES_USER=opentron \
        -e POSTGRES_PASSWORD=opentron_dev \
        -e POSTGRES_DB=opentron \
        -p 5432:5432 \
        -v opentron-postgres-data:/var/lib/postgresql/data \
        postgres:15-alpine
fi

# Wait for PostgreSQL to be ready
echo "Waiting for PostgreSQL to be ready..."
sleep 5

# Verify connection
if docker exec opentron-postgres pg_isready -U opentron > /dev/null 2>&1; then
    echo "✅ PostgreSQL is running on localhost:5432"
    echo "Database: opentron"
    echo "User: opentron"
    echo "Password: opentron_dev"
else
    echo "❌ PostgreSQL failed to start"
    exit 1
fi
