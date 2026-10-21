# Run from a PowerShell 5.1+ terminal with Maven, Java 21, and Docker Desktop available.
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repository
try {
    & (Join-Path $PSScriptRoot 'audit-public-text.ps1')
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw 'JDK 21 is required on PATH.'
    }
    & java (Join-Path $PSScriptRoot 'JavaSyntaxGate.java')
    if ($LASTEXITCODE -ne 0) { throw 'Java syntax gate failed.' }
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        throw 'Maven 3.9+ is required on PATH.'
    }
    if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
        throw 'Docker is required for PostgreSQL integration tests.'
    }
    & docker info --format '{{.ServerVersion}}' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Docker is not running.' }
    & docker compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose validation failed.' }

    & mvn -B -ntp -T 1 clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Maven validation failed.' }

    if (Get-Command git -ErrorAction SilentlyContinue) {
        & git diff --check
        if ($LASTEXITCODE -ne 0) { throw 'Git whitespace check failed.' }
        & git diff --cached --check
        if ($LASTEXITCODE -ne 0) { throw 'Staged Git whitespace check failed.' }
        & git diff --check HEAD~1 HEAD
        if ($LASTEXITCODE -ne 0) { throw 'Latest commit whitespace check failed.' }
        & git show-ref --verify --quiet refs/heads/main
        if ($LASTEXITCODE -eq 0) {
            & git diff --check 'main...HEAD'
            if ($LASTEXITCODE -ne 0) { throw 'Branch whitespace check failed.' }
        }
    }
    Write-Host 'Local build and database integration gates completed.'
}
finally {
    Pop-Location
}
