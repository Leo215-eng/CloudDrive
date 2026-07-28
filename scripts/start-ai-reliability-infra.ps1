[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot '..\docker-compose.ai-reliability.yml'
docker compose -f $composeFile up -d
docker compose -f $composeFile ps

Write-Host 'Started local RocketMQ nameserver/broker and pgvector.'
Write-Host 'Default RocketMQ nameserver: localhost:19876; pgvector: localhost:15432.'
Write-Host 'Override before startup: $env:NETWORKDISK_RMQ_NAMESRV_PORT, $env:NETWORKDISK_RMQ_VIP_PORT, $env:NETWORKDISK_RMQ_LISTEN_PORT, $env:NETWORKDISK_PGVECTOR_PORT.'
Write-Host 'When starting networkdisk-ai, set $env:ROCKETMQ_NAME_SERVER="127.0.0.1:19876" and override pgvector JDBC URL to jdbc:postgresql://localhost:15432/networkdisk_ai.'
Write-Host 'The compose file uses only local development credentials.'
