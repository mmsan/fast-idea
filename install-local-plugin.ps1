[CmdletBinding()]
param(
    [string]$RepositoryUrl = 'http://127.0.0.1:8800/idea-plugins/updatePlugins.xml'
)

$ErrorActionPreference = 'Stop'
$runningIdea = Get-Process -Name 'idea64' -ErrorAction SilentlyContinue
if ($null -ne $runningIdea) {
    throw 'Close all IDEA windows and run this script again.'
}

$configDirectory = Get-ChildItem -LiteralPath (Join-Path $env:APPDATA 'JetBrains') -Directory -Filter 'IntelliJIdea*' -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if ($null -eq $configDirectory) { throw 'The IntelliJ IDEA configuration directory was not found.' }

$customProperties = Join-Path $configDirectory.FullName 'idea.properties'
$customPropertiesText = if (Test-Path -LiteralPath $customProperties) { [IO.File]::ReadAllText($customProperties) } else { '' }
$hostsMatch = [regex]::Match($customPropertiesText, '(?m)^idea\.plugin\.hosts=(.*)$')
if ($hostsMatch.Success) {
    $existingValue = $hostsMatch.Groups[1].Value.Trim()
    $hosts = @(
        $existingValue.Split(';', [StringSplitOptions]::RemoveEmptyEntries) |
            ForEach-Object { $_.Trim().Trim('"') } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Select-Object -Unique
    )
    if ($hosts -notcontains $RepositoryUrl) { $hosts += $RepositoryUrl }
    $newHostsLine = 'idea.plugin.hosts=' + ($hosts -join ';')
    $customPropertiesText = $customPropertiesText.Remove($hostsMatch.Index, $hostsMatch.Length).Insert($hostsMatch.Index, $newHostsLine)
} else {
    if ($customPropertiesText.Length -gt 0 -and -not $customPropertiesText.EndsWith("`n")) { $customPropertiesText += "`r`n" }
    $customPropertiesText += "idea.plugin.hosts=$RepositoryUrl`r`n"
}
[IO.File]::WriteAllText($customProperties, $customPropertiesText, [Text.UTF8Encoding]::new($false))

$repository = [xml](Invoke-WebRequest -Uri $RepositoryUrl -UseBasicParsing -TimeoutSec 10).Content
$plugin = @($repository.plugins.plugin | Where-Object { $_.id -eq 'com.mirror.ideafocusbridge' }) | Select-Object -First 1
if ($null -eq $plugin) { throw 'Fast Idea Bridge was not found in the local plugin repository.' }

$pluginsDirectory = Join-Path $configDirectory.FullName 'plugins'
$targetDirectory = Join-Path $pluginsDirectory 'fast-idea-bridge'
$previousTargetDirectory = Join-Path $pluginsDirectory 'fast-ide-bridge'
$legacyTargetDirectory = Join-Path $pluginsDirectory 'idea-focus-bridge'
$backupRoot = Join-Path $configDirectory.FullName 'plugin-backups'
$backupDirectory = $null
$temporaryZip = Join-Path ([IO.Path]::GetTempPath()) ("fast-idea-bridge-$([guid]::NewGuid()).zip")
New-Item -ItemType Directory -Path $pluginsDirectory -Force | Out-Null
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null

try {
    Invoke-WebRequest -Uri ([string]$plugin.url) -OutFile $temporaryZip -UseBasicParsing -TimeoutSec 30
    $installedDirectory = if (Test-Path -LiteralPath $targetDirectory) { $targetDirectory } elseif (Test-Path -LiteralPath $previousTargetDirectory) { $previousTargetDirectory } elseif (Test-Path -LiteralPath $legacyTargetDirectory) { $legacyTargetDirectory } else { $null }
    if ($null -ne $installedDirectory) {
        $backupDirectory = Join-Path $backupRoot ("fast-idea-bridge-$((Get-Date).ToString('yyyyMMdd-HHmmss'))")
        Move-Item -LiteralPath $installedDirectory -Destination $backupDirectory
    }
    Expand-Archive -LiteralPath $temporaryZip -DestinationPath $pluginsDirectory -Force
    $installedJar = Get-ChildItem -LiteralPath (Join-Path $targetDirectory 'lib') -File -Filter "fast-idea-bridge-$($plugin.version).jar" -ErrorAction SilentlyContinue
    if ($null -eq $installedJar) { throw "Installed plugin version $($plugin.version) could not be verified." }
} catch {
    if (Test-Path -LiteralPath $targetDirectory) { Remove-Item -LiteralPath $targetDirectory -Recurse -Force }
    if ($null -ne $backupDirectory -and (Test-Path -LiteralPath $backupDirectory)) {
        Move-Item -LiteralPath $backupDirectory -Destination $targetDirectory
    }
    throw
} finally {
    if (Test-Path -LiteralPath $temporaryZip) { Remove-Item -LiteralPath $temporaryZip -Force }
}

Write-Host "Fast Idea Bridge $($plugin.version) installation completed. You can start IDEA now." -ForegroundColor Green
Write-Host 'After the first start, enable Update Plugins Automatically in Settings | Plugins.'
