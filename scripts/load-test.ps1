# Load-tests POST /orders and reconciles the result against the database.
#
# Usage:   .\scripts\load-test.ps1 -Concurrency 50 [-Requests 500] [-Stock 100]
# Assumes: the app is running on :8080, user "tester" exists, and product 1
#          has an inventory row. Requires oha (winget install hatoo.oha).

param(
    [int]$Concurrency = 50,
    [int]$Requests = 500,
    [int]$Stock = 100
)
$ErrorActionPreference = 'Stop'

$BaseUrl = 'http://localhost:8080'
$Container = 'flash-sale-postgres'
$ProductId = 1

# SQL goes in through stdin: Windows PowerShell 5.1 strips the inner quotes
# when a SQL string is passed to a native program as an argument.
function Invoke-Sql([string]$sql) {
    $sql | docker exec -i $Container sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -v ON_ERROR_STOP=1 -tA -F ,'
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $sql" }
}

# 1. Reset
Invoke-Sql "TRUNCATE orders RESTART IDENTITY; UPDATE inventory SET stock_quantity = $Stock WHERE product_id = $ProductId;" | Out-Null

# 2. Log in
$auth = @{ username = 'tester'; password = 'password123' } | ConvertTo-Json
$token = (Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/login" -ContentType 'application/json' -Body $auth).token

# 3. Load test. The body is read from a file for the same quoting reason as above;
#    WriteAllText writes UTF-8 without a BOM.
$bodyFile = Join-Path $env:TEMP 'flash-sale-order-body.json'
[IO.File]::WriteAllText($bodyFile, "{`"productId`":$ProductId,`"quantity`":1}")

$result = oha -n $Requests -c $Concurrency --no-tui --output-format json -m POST `
    -H "Authorization: Bearer $token" -T application/json -D $bodyFile "$BaseUrl/orders" |
    ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'oha failed' }

# 4. Reconcile
$stockLeft, $orders, $sold = (Invoke-Sql "SELECT (SELECT stock_quantity FROM inventory WHERE product_id = $ProductId), COUNT(*), COALESCE(SUM(quantity), 0) FROM orders;").Split(',')
$oversold = [int]$sold - $Stock

# 5. Report
$codes = ($result.statusCodeDistribution.PSObject.Properties | Sort-Object Name |
    ForEach-Object { "$($_.Name) x $($_.Value)" }) -join ', '

Write-Host "concurrency $Concurrency, requests $Requests, initial stock $Stock"
Write-Host "  status codes: $codes"
Write-Host "  sold:         $sold"
Write-Host "  stock left:   $stockLeft"
Write-Host ('  rps:          {0:N0}' -f $result.summary.requestsPerSec)
Write-Host ('  avg latency:  {0:N1} ms' -f ($result.summary.average * 1000))
if ($oversold -gt 0) {
    Write-Host "  OVERSOLD by $oversold" -ForegroundColor Red
} else {
    Write-Host '  no oversell' -ForegroundColor Green
}
