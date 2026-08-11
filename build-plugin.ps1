[CmdletBinding()]
param(
    [switch]$Verify
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$propertiesPath = Join-Path $projectRoot 'gradle.properties'
$propertiesText = [IO.File]::ReadAllText($propertiesPath)
$versionMatch = [regex]::Match($propertiesText, '(?m)^pluginVersion=(\d+\.\d+\.\d+)$')
if (-not $versionMatch.Success) { throw 'pluginVersion was not found in gradle.properties.' }
$version = $versionMatch.Groups[1].Value

$javaHomeForBuild = $env:JAVA_HOME
if ([string]::IsNullOrWhiteSpace($javaHomeForBuild) -or -not (Test-Path -LiteralPath (Join-Path $javaHomeForBuild 'bin\java.exe'))) {
    $javaExe = Get-ChildItem -LiteralPath 'C:\Program Files\JetBrains' -Recurse -File -Filter 'java.exe' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\jbr\\bin\\java\.exe$' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($null -eq $javaExe) { throw 'JAVA_HOME and JetBrains Runtime were not found.' }
    $javaHomeForBuild = Split-Path (Split-Path $javaExe.FullName -Parent) -Parent
}

$savedErrorPreference = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$javaVersionOutput = & (Join-Path $javaHomeForBuild 'bin\java.exe') -version 2>&1 | Out-String
$ErrorActionPreference = $savedErrorPreference
$javaMajorMatch = [regex]::Match($javaVersionOutput, 'version "(?:1\.)?(\d+)')
if (-not $javaMajorMatch.Success) { throw 'Could not detect the Java major version.' }
$javaMajor = $javaMajorMatch.Groups[1].Value

$localGradle = Join-Path $projectRoot '.gradle-dist\gradle-9.0.0\bin\gradle.bat'
$gradleCommand = if (Test-Path -LiteralPath $localGradle) { $localGradle } else { Join-Path $projectRoot 'gradlew.bat' }
$tasks = @('buildPlugin')
if ($Verify) { $tasks += @('verifyPluginProjectConfiguration', 'verifyPluginStructure') }

$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $javaHomeForBuild
    & $gradleCommand @tasks "-PjavaToolchainVersion=$javaMajor"
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }
} finally {
    $env:JAVA_HOME = $previousJavaHome
}

$artifact = Join-Path $projectRoot "build\distributions\fast-idea-bridge-$version.zip"
if (-not (Test-Path -LiteralPath $artifact)) { throw "Build succeeded but the plugin archive was not found: $artifact" }
$item = Get-Item -LiteralPath $artifact
Write-Host 'Plugin package generated successfully.' -ForegroundColor Green
Write-Host "Path: $($item.FullName)"
Write-Host "Size: $($item.Length) bytes"
