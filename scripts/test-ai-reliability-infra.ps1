[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$composeFile = Join-Path $PSScriptRoot '..\docker-compose.ai-reliability.yml'
docker compose -f $composeFile ps

& docker exec networkdisk-pgvector pg_isready -U postgres -d networkdisk_ai
$previousErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$cluster = & docker exec networkdisk-rocketmq-namesrv sh mqadmin clusterList -n localhost:9876 2>&1
$ErrorActionPreference = $previousErrorAction
$clusterText = [string]::Join([Environment]::NewLine, [string[]]$cluster)
# brokerIP1=127.0.0.1 是供宿主机客户端使用的地址；在 NameServer 容器内回连会指向容器自身，
# 因此只读取此命令输出中的路由注册地址，不能以其退出码判断 Broker 的宿主机可达性。
$clusterText | Write-Host
$listenPort = if ($env:NETWORKDISK_RMQ_LISTEN_PORT) { $env:NETWORKDISK_RMQ_LISTEN_PORT } else { '20911' }
if ($clusterText -notmatch ("127\.0\.0\.1:" + $listenPort)) {
    throw "RocketMQ broker did not register the host-reachable address 127.0.0.1:$listenPort."
}
if (-not (Test-NetConnection -ComputerName '127.0.0.1' -Port ([int]$listenPort) -InformationLevel Quiet)) {
    throw "Host cannot reach RocketMQ broker at 127.0.0.1:$listenPort."
}

Write-Host 'Infrastructure health checks passed. Host addresses: RocketMQ localhost:19876; pgvector localhost:15432 (unless NETWORKDISK_*_PORT overrides were set before startup).'
