[CmdletBinding()]
param([switch]$RemoveData)

$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot '..\docker-compose.ai-reliability.yml'
if ($RemoveData) {
    docker compose -f $composeFile down -v
} else {
    docker compose -f $composeFile down
}
