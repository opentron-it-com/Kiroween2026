# Start PostgreSQL in Docker (Windows PowerShell)

Write-Host "Checking Docker..." -ForegroundColor Cyan

# Check if Docker is running
try {
    $dockerVersion = docker --version
    Write-Host "✅ Docker found: $dockerVersion" -ForegroundColor Green
} catch {
    Write-Host "❌ Docker not found. Install Docker Desktop from https://www.docker.com/products/docker-desktop" -ForegroundColor Red
    exit 1
}

# Check if postgres container exists
$containerExists = docker ps -a --format "{{.Names}}" | Select-String "^opentron-postgres$"

if ($containerExists) {
    Write-Host "Starting existing PostgreSQL container..." -ForegroundColor Cyan
    docker start opentron-postgres
} else {
    Write-Host "Creating new PostgreSQL container..." -ForegroundColor Cyan
    docker run -d `
        --name opentron-postgres `
        -e POSTGRES_USER=opentron `
        -e POSTGRES_PASSWORD=opentron_dev `
        -e POSTGRES_DB=opentron `
        -p 5432:5432 `
        -v opentron-postgres-data:/var/lib/postgresql/data `
        postgres:15-alpine
}

# Wait for PostgreSQL to be ready
Write-Host "Waiting for PostgreSQL to be ready..." -ForegroundColor Cyan
Start-Sleep -Seconds 5

# Verify connection
$ready = docker exec opentron-postgres pg_isready -U opentron 2>&1
if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ PostgreSQL is running on localhost:5432" -ForegroundColor Green
    Write-Host "Database: opentron" -ForegroundColor Green
    Write-Host "User: opentron" -ForegroundColor Green
    Write-Host "Password: opentron_dev" -ForegroundColor Green
    Write-Host "" -ForegroundColor Green
    Write-Host "Now run: mvn spring-boot:run" -ForegroundColor Cyan
} else {
    Write-Host "❌ PostgreSQL failed to start" -ForegroundColor Red
    docker logs opentron-postgres
    exit 1
}
