[CmdletBinding()]
param(
    [switch]$Verify,
    [switch]$VerifyCompatibility,
    [string]$ProjectCacheDirectory
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$propertiesPath = Join-Path $projectRoot 'gradle.properties'
$propertiesText = [IO.File]::ReadAllText($propertiesPath)
$versionMatch = [regex]::Match($propertiesText, '(?m)^pluginVersion=(\d+\.\d+\.\d+)\r?$')
if (-not $versionMatch.Success) { throw 'pluginVersion was not found in gradle.properties.' }
$version = $versionMatch.Groups[1].Value

$javaHomeForBuild = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.8-hotspot'
if (-not (Test-Path -LiteralPath (Join-Path $javaHomeForBuild 'bin\java.exe'))) {
    throw "The required standalone JDK 17 was not found: $javaHomeForBuild"
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
if ($Verify -or $VerifyCompatibility) { $tasks += @('verifyPluginProjectConfiguration', 'verifyPluginStructure') }
if ($VerifyCompatibility) { $tasks += 'verifyPlugin' }
$projectCacheDirectoryForBuild = if ([string]::IsNullOrWhiteSpace($ProjectCacheDirectory)) {
    Join-Path $projectRoot '.intellijPlatform-build\gradle-cache'
} else {
    [IO.Path]::GetFullPath($ProjectCacheDirectory)
}
New-Item -ItemType Directory -Path $projectCacheDirectoryForBuild -Force | Out-Null
$gradleArguments = @($tasks + @('--project-cache-dir', $projectCacheDirectoryForBuild))
if ($env:HTTPS_PROXY) {
    $buildProxy = [uri]$env:HTTPS_PROXY
    $gradleArguments += @("-Dhttps.proxyHost=$($buildProxy.Host)", "-Dhttps.proxyPort=$($buildProxy.Port)",
        "-Dhttp.proxyHost=$($buildProxy.Host)", "-Dhttp.proxyPort=$($buildProxy.Port)")
}

$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $javaHomeForBuild
    & $gradleCommand @gradleArguments "-PjavaToolchainVersion=$javaMajor"
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
