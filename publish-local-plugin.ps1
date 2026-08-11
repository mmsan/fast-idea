[CmdletBinding()]
param(
    [string]$PublishDirectory = 'D:\docker\www\idea-plugins',
    [string]$RepositoryBaseUrl = 'http://127.0.0.1:8800/idea-plugins',
    [switch]$NoVersionBump
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$propertiesPath = Join-Path $projectRoot 'gradle.properties'
$propertiesText = [IO.File]::ReadAllText($propertiesPath)
$originalPropertiesText = $propertiesText
$versionChanged = $false
$versionMatch = [regex]::Match($propertiesText, '(?m)^pluginVersion=(\d+)\.(\d+)\.(\d+)$')
if (-not $versionMatch.Success) { throw 'pluginVersion in x.y.z format was not found in gradle.properties.' }

$oldVersion = $versionMatch.Value.Substring(14)
if ($NoVersionBump) {
    $newVersion = $oldVersion
} else {
    $newVersion = '{0}.{1}.{2}' -f $versionMatch.Groups[1].Value, $versionMatch.Groups[2].Value, ([int]$versionMatch.Groups[3].Value + 1)
    $propertiesText = $propertiesText.Remove($versionMatch.Index, $versionMatch.Length).Insert($versionMatch.Index, "pluginVersion=$newVersion")
    [IO.File]::WriteAllText($propertiesPath, $propertiesText, [Text.UTF8Encoding]::new($false))
    $versionChanged = $true
}

try {
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

$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $javaHomeForBuild
    $localGradle = Join-Path $projectRoot '.gradle-dist\gradle-9.0.0\bin\gradle.bat'
    $gradleCommand = if (Test-Path -LiteralPath $localGradle) { $localGradle } else { Join-Path $projectRoot 'gradlew.bat' }
    & $gradleCommand buildPlugin "-PjavaToolchainVersion=$javaMajor"
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }
} finally {
    $env:JAVA_HOME = $previousJavaHome
}

$zipName = "fast-idea-bridge-$newVersion.zip"
$sourceZip = Join-Path $projectRoot "build\distributions\$zipName"
if (-not (Test-Path -LiteralPath $sourceZip)) { throw "Build artifact was not found: $sourceZip" }

New-Item -ItemType Directory -Path $PublishDirectory -Force | Out-Null
$publishedZip = Join-Path $PublishDirectory $zipName
Copy-Item -LiteralPath $sourceZip -Destination $publishedZip -Force

$downloadUrl = "$($RepositoryBaseUrl.TrimEnd('/'))/$zipName"
$xml = @"
<?xml version="1.0" encoding="UTF-8"?>
<plugins>
  <plugin id="com.mirror.ideafocusbridge" url="$downloadUrl" version="$newVersion">
    <idea-version since-build="241"/>
    <name>Fast Idea Bridge</name>
    <description>Focus and editor interaction bridge between JetBrains IDEs and the Fast application.</description>
  </plugin>
</plugins>
"@
$repositoryFile = Join-Path $PublishDirectory 'updatePlugins.xml'
$temporaryFile = "$repositoryFile.tmp"
[IO.File]::WriteAllText($temporaryFile, $xml, [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $temporaryFile -Destination $repositoryFile -Force

$repositoryUrl = "$($RepositoryBaseUrl.TrimEnd('/'))/updatePlugins.xml"
$response = Invoke-WebRequest -Uri $repositoryUrl -UseBasicParsing -TimeoutSec 5
if ($response.StatusCode -ne 200) { throw "Nginx repository check failed: HTTP $($response.StatusCode)" }

Write-Host "Published version: $newVersion" -ForegroundColor Green
Write-Host "Plugin: $downloadUrl"
Write-Host "Repository: $repositoryUrl"
} catch {
    if ($versionChanged) {
        [IO.File]::WriteAllText($propertiesPath, $originalPropertiesText, [Text.UTF8Encoding]::new($false))
    }
    throw
}
