$ErrorActionPreference = 'Stop'
$javaHome = (Resolve-Path (Join-Path $PSScriptRoot '..\..\Network\runtime\java')).Path
$env:JAVA_HOME = $javaHome
$env:Path = (Join-Path $javaHome 'bin') + [IO.Path]::PathSeparator + $env:Path
$maven = Join-Path $PSScriptRoot '..\.tools\apache-maven-3.9.11\bin\mvn.cmd'
if (-not (Test-Path -LiteralPath $maven)) { $maven = 'mvn' }
& $maven -f (Join-Path $PSScriptRoot 'pom.xml') clean test package
if ($LASTEXITCODE -ne 0) { throw 'NidoBuilds build failed.' }
