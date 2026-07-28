[CmdletBinding()]
param(
    [ValidateSet('local', 'fastdfs')]
    [string]$StorageProfile = 'local',
    [switch]$Build,
    [string]$JavaCommand = 'java'
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$modulePath = Join-Path $projectRoot 'networkdisk-business\networkdisk-files'
$jarPath = Join-Path $modulePath 'target\networkdisk-files-1.0.0-SNAPSHOT.jar'
$runtimeLogDirectory = Join-Path $projectRoot 'logs\files-ha'

New-Item -ItemType Directory -Force -Path $runtimeLogDirectory | Out-Null

if ($Build) {
    $mavenPath = Join-Path $projectRoot '.tools\apache-maven-3.9.9\bin\mvn.cmd'
    if (-not (Test-Path -LiteralPath $mavenPath)) {
        throw 'Maven was not found. Install Maven or build the networkdisk-files jar before running this script.'
    }
    & $mavenPath -pl networkdisk-business/networkdisk-files -am -DskipTests package
    if ($LASTEXITCODE -ne 0) {
        throw 'Maven packaging failed; dual instances were not started.'
    }
}

if (-not (Test-Path -LiteralPath $jarPath)) {
    throw "Missing executable jar: $jarPath. Run with -Build after Maven dependencies are available."
}

function Start-FileServiceInstance {
    param(
        [string]$InstanceName,
        [int]$HttpPort,
        [int]$QosPort,
        [int]$DubboPort
    )

    $profiles = "$StorageProfile,files-instance-$InstanceName"
    $arguments = @(
        '-jar', $jarPath,
        "--spring.profiles.active=$profiles",
        "--server.port=$HttpPort",
        "--spring.cloud.nacos.discovery.instance-id=networkdisk-files-$InstanceName-$HttpPort",
        "--spring.cloud.nacos.discovery.metadata.local-instance=$InstanceName",
        "--dubbo.application.qos-port=$QosPort",
        "--dubbo.protocol.port=$DubboPort"
    )
    $stdoutPath = Join-Path $runtimeLogDirectory "networkdisk-files-$InstanceName.out.log"
    $stderrPath = Join-Path $runtimeLogDirectory "networkdisk-files-$InstanceName.err.log"
    Start-Process -FilePath $JavaCommand -ArgumentList $arguments -WorkingDirectory $projectRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
}

$instanceA = Start-FileServiceInstance -InstanceName 'a' -HttpPort 8082 -QosPort 22222 -DubboPort 20882
$instanceB = Start-FileServiceInstance -InstanceName 'b' -HttpPort 8083 -QosPort 22223 -DubboPort 20883

Write-Host "Started networkdisk-files A (PID $($instanceA.Id), HTTP 8082) and B (PID $($instanceB.Id), HTTP 8083)."
Write-Host 'Prerequisite: Nacos must already be reachable at the configured address.'
Write-Host 'Gateway route lb://networkdisk-files can load-balance both registered HTTP instances.'
Write-Host 'A direct Vite proxy targets only its configured backend port; it is not a load balancer.'
Write-Host "Startup logs: $runtimeLogDirectory"
