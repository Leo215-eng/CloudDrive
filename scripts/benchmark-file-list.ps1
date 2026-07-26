<#
.SYNOPSIS
  Read-only load test for the network-disk file-list endpoint.

.EXAMPLE
  .\scripts\benchmark-file-list.ps1 -Token '<sa-token>' -Concurrency 30 -DurationSeconds 60
#>
[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8082',
    [Parameter(Mandatory = $true)]
    [string]$Token,
    [int]$Concurrency = 10,
    [int]$DurationSeconds = 60,
    [string]$ParentId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Concurrency -lt 1 -or $DurationSeconds -lt 1) {
    throw 'Concurrency and DurationSeconds must both be at least 1.'
}

$uri = "$($BaseUrl.TrimEnd('/'))/api/v1/files/folders-files"
if ($ParentId) {
    $uri += '?parentId=' + [uri]::EscapeDataString($ParentId)
}

# Each job makes only GET requests, so this script does not alter application data.
$jobs = 1..$Concurrency | ForEach-Object {
    Start-Job -ArgumentList $uri, $Token, $DurationSeconds -ScriptBlock {
        param($RequestUri, $SaToken, $Seconds)

        $headers = @{ satoken = $SaToken }
        $deadline = [DateTime]::UtcNow.AddSeconds($Seconds)
        $records = New-Object System.Collections.Generic.List[object]

        while ([DateTime]::UtcNow -lt $deadline) {
            $watch = [System.Diagnostics.Stopwatch]::StartNew()
            try {
                $response = Invoke-WebRequest -Uri $RequestUri -Headers $headers -Method Get -UseBasicParsing
                $watch.Stop()
                $records.Add([pscustomobject]@{
                    Success = ($response.StatusCode -ge 200 -and $response.StatusCode -lt 300)
                    StatusCode = $response.StatusCode
                    ElapsedMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 2)
                })
            }
            catch {
                $watch.Stop()
                $statusCode = 0
                if ($_.Exception.Response) { $statusCode = [int]$_.Exception.Response.StatusCode }
                $records.Add([pscustomobject]@{
                    Success = $false
                    StatusCode = $statusCode
                    ElapsedMs = [math]::Round($watch.Elapsed.TotalMilliseconds, 2)
                })
            }
        }
        $records
    }
}

$results = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job -Force

if (-not $results) {
    throw 'No samples were collected. Confirm that the service is running and the token is valid.'
}

$latencies = @($results | ForEach-Object { [double]$_.ElapsedMs } | Sort-Object)
$count = $latencies.Count
$p95Index = [math]::Min($count - 1, [math]::Ceiling($count * 0.95) - 1)
$successCount = @($results | Where-Object Success).Count
$errorCount = $count - $successCount

[pscustomobject]@{
    Endpoint = $uri
    Concurrency = $Concurrency
    DurationSeconds = $DurationSeconds
    Samples = $count
    Successes = $successCount
    Errors = $errorCount
    ErrorRatePercent = [math]::Round(($errorCount / $count) * 100, 2)
    ThroughputRps = [math]::Round($count / $DurationSeconds, 2)
    AverageMs = [math]::Round(($latencies | Measure-Object -Average).Average, 2)
    P95Ms = $latencies[$p95Index]
    MaxMs = $latencies[$count - 1]
} | Format-List
