[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot '..\docker-compose.local.yml'

docker compose -f $composeFile up -d
docker compose -f $composeFile ps

Write-Host ''
Write-Host 'Local infrastructure is starting.'
Write-Host 'MySQL: localhost:3306 (root / 123456)'
Write-Host 'Redis: localhost:6379 (password: 123456)'
Write-Host 'Nacos: http://localhost:8848/nacos'
