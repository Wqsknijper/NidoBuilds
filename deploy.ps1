$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'build.ps1')
$destination = Join-Path $PSScriptRoot '..\..\Network\artifacts\plugins\NidoBuilds.jar'
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $destination) | Out-Null
Copy-Item -Force -LiteralPath (Join-Path $PSScriptRoot 'target\NidoBuilds.jar') -Destination $destination
Write-Host "NidoBuilds deployed to $destination"
