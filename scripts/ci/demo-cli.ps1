$ErrorActionPreference = "Stop"

Set-Location "$PSScriptRoot/../.."

Write-Host "Building CLI fat jar..."
mvn -pl contract-cli -am package

$jar = "contract-cli/target/contract-cli-0.1.0-SNAPSHOT-all.jar"

Write-Host ""
Write-Host "Running lint..."
java -jar $jar lint --path contracts/orders.created

Write-Host ""
Write-Host "Running diff..."
java -jar $jar diff --base contracts/orders.created/v1.json --candidate contracts/orders.created/v2.json

Write-Host ""
Write-Host "Running compatibility check..."
java -jar $jar check-compat --base contracts/orders.created/v1.json --candidate contracts/orders.created/v2.json --mode BACKWARD
