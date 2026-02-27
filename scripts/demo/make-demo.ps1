$ErrorActionPreference = "Stop"

$root = Resolve-Path "$PSScriptRoot/../.."
Set-Location $root

Write-Host "Building CLI fat jar..."
mvn -pl contract-cli -am package

$jar = "contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar"
if (-not (Test-Path $jar)) {
  throw "CLI jar not found at $jar"
}

Write-Host "Recording a sample compatibility check into checks.db..."
java -jar $jar check-compat `
  --base contracts/orders.created/v1.json `
  --candidate contracts/orders.created/v2.json `
  --mode BACKWARD `
  --record-db checks.db `
  --contract-id orders.created `
  --commit-sha demo-run

Write-Host "Starting contract-service..."
$serviceProcess = Start-Process `
  -FilePath "mvn" `
  -ArgumentList "spring-boot:run" `
  -WorkingDirectory "$root/contract-service" `
  -PassThru

try {
  $maxAttempts = 60
  $attempt = 0
  $ready = $false

  while ($attempt -lt $maxAttempts) {
    $attempt++
    Start-Sleep -Seconds 1
    try {
      $status = curl.exe -s http://localhost:8080/api/status
      if ($LASTEXITCODE -eq 0 -and $status) {
        $ready = $true
        break
      }
    } catch {
      # wait for service startup
    }
  }

  if (-not $ready) {
    throw "Service did not become ready on http://localhost:8080 within timeout."
  }

  Write-Host ""
  Write-Host "Swagger UI: http://localhost:8080/swagger-ui/index.html"
  Write-Host "OpenAPI JSON: http://localhost:8080/v3/api-docs"
  Write-Host ""
  Write-Host "GET /contracts"
  curl.exe -s http://localhost:8080/contracts
  Write-Host ""
  Write-Host ""
  Write-Host "GET /checks?contractId=orders.created"
  curl.exe -s "http://localhost:8080/checks?contractId=orders.created"
  Write-Host ""
} finally {
  if ($serviceProcess -and -not $serviceProcess.HasExited) {
    Write-Host ""
    Write-Host "Stopping contract-service..."
    Stop-Process -Id $serviceProcess.Id -Force
  }
}
