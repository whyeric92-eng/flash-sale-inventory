# Load-tests GET /products/{id} and counts how many database transactions it caused.
#
# Usage:   .\scripts\load-test-product.ps1 -Concurrency 50 [-Requests 5000]
# Assumes: the app is running on :8080, user "tester" exists, and product 1 exists.
#          Requires oha (winget install hatoo.oha).

param(
    [int]$Concurrency = 50,
    [int]$Requests = 5000
)
$ErrorActionPreference = 'Stop'

$BaseUrl = 'http://localhost:8080'
$Container = 'flash-sale-postgres'
$RedisContainer = 'flash-sale-redis'
$ProductId = 1

# Committed transactions in this database so far. Postgres publishes these counters
# with a short delay, so callers wait a moment before reading them.
function Get-DbTransactions {
    $sql = 'SELECT xact_commit FROM pg_stat_database WHERE datname = current_database();'
    $count = $sql | docker exec -i $Container sh -c 'psql -U $POSTGRES_USER -d $POSTGRES_DB -v ON_ERROR_STOP=1 -tA'
    if ($LASTEXITCODE -ne 0) { throw 'psql failed' }
    [long]$count
}

# 1. Log in
$auth = @{ username = 'tester'; password = 'password123' } | ConvertTo-Json
$token = (Invoke-RestMethod -Method Post -Uri "$BaseUrl/auth/login" -ContentType 'application/json' -Body $auth).token
$headers = @{ Authorization = "Bearer $token" }

# 2. Start from an empty cache, then read once so the run measures the warm path.
#    Without the cache (main branch) the key is never written and this changes nothing.
docker exec $RedisContainer redis-cli DEL "product:$ProductId" | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'redis-cli failed' }
Invoke-RestMethod -Uri "$BaseUrl/products/$ProductId" -Headers $headers | Out-Null

Start-Sleep -Seconds 2
$before = Get-DbTransactions

# 3. Load test
$result = oha -n $Requests -c $Concurrency --no-tui --output-format json `
    -H "Authorization: Bearer $token" "$BaseUrl/products/$ProductId" |
    ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'oha failed' }

Start-Sleep -Seconds 2
$dbTransactions = (Get-DbTransactions) - $before

# 4. Report
$codes = ($result.statusCodeDistribution.PSObject.Properties | Sort-Object Name |
    ForEach-Object { "$($_.Name) x $($_.Value)" }) -join ', '

Write-Host "GET /products/$ProductId, concurrency $Concurrency, requests $Requests"
Write-Host "  status codes:    $codes"
Write-Host ('  rps:             {0:N0}' -f $result.summary.requestsPerSec)
Write-Host ('  avg latency:     {0:N1} ms' -f ($result.summary.average * 1000))
Write-Host ('  p99 latency:     {0:N1} ms' -f ($result.latencyPercentiles.p99 * 1000))
Write-Host "  db transactions: $dbTransactions"
