[CmdletBinding()]
param(
    [string]$PublishDirectory = 'D:\docker\www\idea-plugins',
    [string]$RepositoryBaseUrl = 'http://127.0.0.1:8800/idea-plugins',
    [switch]$NoVersionBump,
    [string]$ProjectCacheDirectory,
    [string]$IntelliJPlatformCacheDirectory,
    [string]$SandboxContainer,
    [string]$BuildDirectory
)

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$releaseRoot = Join-Path $projectRoot '.intellijPlatform-release'
$ProjectCacheDirectory = if ([string]::IsNullOrWhiteSpace($ProjectCacheDirectory)) { Join-Path $releaseRoot 'gradle-cache' } else { [IO.Path]::GetFullPath($ProjectCacheDirectory) }
$IntelliJPlatformCacheDirectory = if ([string]::IsNullOrWhiteSpace($IntelliJPlatformCacheDirectory)) { Join-Path $releaseRoot 'platform-cache' } else { [IO.Path]::GetFullPath($IntelliJPlatformCacheDirectory) }
$SandboxContainer = if ([string]::IsNullOrWhiteSpace($SandboxContainer)) { Join-Path $releaseRoot 'sandbox' } else { [IO.Path]::GetFullPath($SandboxContainer) }
$BuildDirectory = if ([string]::IsNullOrWhiteSpace($BuildDirectory)) { Join-Path $releaseRoot 'build' } else { [IO.Path]::GetFullPath($BuildDirectory) }
$propertiesPath = Join-Path $projectRoot 'gradle.properties'
$propertiesText = [IO.File]::ReadAllText($propertiesPath)
$originalPropertiesText = $propertiesText
$versionChanged = $false
$publishedZip = $null
$publishedZipExisted = $false
$repositoryFile = Join-Path $PublishDirectory 'updatePlugins.xml'
$temporaryFile = "$repositoryFile.tmp"
$repositoryExisted = Test-Path -LiteralPath $repositoryFile
$originalRepositoryText = if ($repositoryExisted) { [IO.File]::ReadAllText($repositoryFile) } else { $null }
$repositoryReplaced = $false
$temporaryInitScript = $null
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

$previousJavaHome = $env:JAVA_HOME
try {
    $env:JAVA_HOME = $javaHomeForBuild
    $localGradle = Join-Path $projectRoot '.gradle-dist\gradle-9.0.0\bin\gradle.bat'
    $gradleCommand = if (Test-Path -LiteralPath $localGradle) { $localGradle } else { Join-Path $projectRoot 'gradlew.bat' }
    $gradleArguments = @('buildPlugin', "-PjavaToolchainVersion=$javaMajor")
    if (-not [string]::IsNullOrWhiteSpace($ProjectCacheDirectory)) {
        New-Item -ItemType Directory -Path $ProjectCacheDirectory -Force | Out-Null
        $gradleArguments += @('--project-cache-dir', $ProjectCacheDirectory)
    }
    if (-not [string]::IsNullOrWhiteSpace($IntelliJPlatformCacheDirectory)) {
        New-Item -ItemType Directory -Path $IntelliJPlatformCacheDirectory -Force | Out-Null
        $gradleArguments += "-Porg.jetbrains.intellij.platform.intellijPlatformCache=$IntelliJPlatformCacheDirectory"
    }
    if (-not [string]::IsNullOrWhiteSpace($SandboxContainer)) {
        New-Item -ItemType Directory -Path $SandboxContainer -Force | Out-Null
        $sandboxPath = $SandboxContainer.Replace('\', '/')
        $buildPath = $BuildDirectory.Replace('\', '/')
        $temporaryInitScript = Join-Path ([IO.Path]::GetTempPath()) ('fast-idea-publish-' + [guid]::NewGuid().ToString('N') + '.gradle')
        $initScript = @"
allprojects {
    layout.buildDirectory.set(file('$buildPath'))
    afterEvaluate {
        def extension = extensions.findByName('intellijPlatform')
        if (extension != null) {
            extension.sandboxContainer.set(file('$sandboxPath'))
        }
    }
}
"@
        [IO.File]::WriteAllText($temporaryInitScript, $initScript, [Text.UTF8Encoding]::new($false))
        $gradleArguments += @('-I', $temporaryInitScript)
    }
    & $gradleCommand @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Gradle build failed with exit code $LASTEXITCODE." }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    if ($null -ne $temporaryInitScript -and (Test-Path -LiteralPath $temporaryInitScript)) {
        Remove-Item -LiteralPath $temporaryInitScript -Force -ErrorAction SilentlyContinue
    }
}

$zipName = "fast-idea-bridge-$newVersion.zip"
$sourceZip = Join-Path $BuildDirectory "distributions\$zipName"
if (-not (Test-Path -LiteralPath $sourceZip)) { throw "Build artifact was not found: $sourceZip" }

New-Item -ItemType Directory -Path $PublishDirectory -Force | Out-Null
$publishedZip = Join-Path $PublishDirectory $zipName
$publishedZipExisted = Test-Path -LiteralPath $publishedZip
Copy-Item -LiteralPath $sourceZip -Destination $publishedZip -Force

$downloadUrl = "$($RepositoryBaseUrl.TrimEnd('/'))/$zipName"
$zipResponse = Invoke-WebRequest -Uri $downloadUrl -Method Head -UseBasicParsing -TimeoutSec 5
if ($zipResponse.StatusCode -ne 200) {
    throw "Plugin ZIP repository check failed: HTTP $($zipResponse.StatusCode) ($downloadUrl)"
}
$zipContentLength = 0L
[long]::TryParse([string]$zipResponse.Headers['Content-Length'], [ref]$zipContentLength) | Out-Null
if ($zipContentLength -le 0) {
    throw "Plugin ZIP repository check failed: empty response ($downloadUrl)"
}

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
[IO.File]::WriteAllText($temporaryFile, $xml, [Text.UTF8Encoding]::new($false))
Move-Item -LiteralPath $temporaryFile -Destination $repositoryFile -Force
$repositoryReplaced = $true

$repositoryUrl = "$($RepositoryBaseUrl.TrimEnd('/'))/updatePlugins.xml"
$response = Invoke-WebRequest -Uri $repositoryUrl -UseBasicParsing -TimeoutSec 5
if ($response.StatusCode -ne 200) { throw "Nginx repository check failed: HTTP $($response.StatusCode)" }
$publishedXml = [xml]$response.Content
$publishedPlugin = @($publishedXml.plugins.plugin | Where-Object { $_.id -eq 'com.mirror.ideafocusbridge' }) | Select-Object -First 1
if ($null -eq $publishedPlugin -or $publishedPlugin.version -ne $newVersion -or $publishedPlugin.url -ne $downloadUrl) {
    throw "Published updatePlugins.xml does not reference version $newVersion and its verified ZIP URL."
}

Write-Host "Published version: $newVersion" -ForegroundColor Green
Write-Host "Plugin: $downloadUrl"
Write-Host "Repository: $repositoryUrl"
} catch {
    if (Test-Path -LiteralPath $temporaryFile) {
        Remove-Item -LiteralPath $temporaryFile -Force -ErrorAction SilentlyContinue
    }
    if ($repositoryReplaced) {
        try {
            if ($repositoryExisted) {
                $rollbackFile = "$repositoryFile.rollback.tmp"
                [IO.File]::WriteAllText($rollbackFile, $originalRepositoryText, [Text.UTF8Encoding]::new($false))
                Move-Item -LiteralPath $rollbackFile -Destination $repositoryFile -Force
            } elseif (Test-Path -LiteralPath $repositoryFile) {
                Remove-Item -LiteralPath $repositoryFile -Force
            }
        } catch {
            Write-Warning "Failed to restore updatePlugins.xml: $($_.Exception.Message)"
        }
    }
    if ($null -ne $publishedZip -and -not $publishedZipExisted -and (Test-Path -LiteralPath $publishedZip)) {
        Remove-Item -LiteralPath $publishedZip -Force -ErrorAction SilentlyContinue
    }
    if ($versionChanged) {
        [IO.File]::WriteAllText($propertiesPath, $originalPropertiesText, [Text.UTF8Encoding]::new($false))
    }
    throw
}
