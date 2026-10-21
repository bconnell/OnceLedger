$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$repository = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $repository
try {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
        throw 'JDK 21 is required for the repository text audit.'
    }
    & java (Join-Path $PSScriptRoot 'PublicTextGate.java')
    if ($LASTEXITCODE -ne 0) { throw 'Public-text audit failed.' }
}
finally {
    Pop-Location
}
