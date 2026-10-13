#requires -Version 7.0
[CmdletBinding()]
param(
    [switch]$Plan,
    [switch]$PrepareOnly,
    [switch]$Resume,
    [switch]$ConfigureCredentials,
    [ValidatePattern('^\d+\.\d+\.\d+$')][string]$Version,
    [ValidateSet('JetBrains', 'VisualStudio', 'VSCode')][string[]]$ConfirmSubmitted = @(),
    [string]$VisualStudioRoot = (Join-Path (Split-Path $PSScriptRoot) 'fast-visual-studio'),
    [string]$VSCodeRoot = (Join-Path (Split-Path $PSScriptRoot) 'fast-vscode')
)

$ErrorActionPreference = 'Stop'
$releaseRoot = Join-Path $PSScriptRoot '.marketplace-release'
$statePath = Join-Path $releaseRoot 'latest.json'
$ideaProperties = Join-Path $PSScriptRoot 'gradle.properties'
$vsManifest = Join-Path $VisualStudioRoot 'FastVisualStudioBridge/source.extension.vsixmanifest'
$vsAssembly = Join-Path $VisualStudioRoot 'FastVisualStudioBridge/AssemblyInfo.cs'
$codePackage = Join-Path $VSCodeRoot 'package.json'
$codeLock = Join-Path $VSCodeRoot 'package-lock.json'
$credentialPath = Join-Path $env:LOCALAPPDATA 'FastGoTo/marketplace-credentials.xml'

if ($ConfigureCredentials) {
    if ($Plan -or $PrepareOnly -or $Resume -or $Version -or $ConfirmSubmitted.Count) { throw '-ConfigureCredentials must be used alone.' }
    $credentials = @{
        JetBrains = Read-Host 'JetBrains Marketplace token' -AsSecureString
        Microsoft = Read-Host 'Microsoft Marketplace PAT (mmsan, Marketplace Manage)' -AsSecureString
    }
    if (!$credentials.JetBrains.Length -or !$credentials.Microsoft.Length) { throw 'Both tokens are required; nothing was saved.' }
    New-Item -ItemType Directory -Path (Split-Path $credentialPath) -Force | Out-Null
    $credentials | Export-Clixml -LiteralPath $credentialPath
    Write-Host "Credentials encrypted for this Windows user: $credentialPath"
    exit 0
}

function Invoke-ReleaseCommand {
    <# Runs a native tool and preserves a nonzero exit as a release failure. #>
    param([string]$Command, [string[]]$Arguments)
    & $Command @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$([IO.Path]::GetFileName($Command)) failed (exit $LASTEXITCODE)." }
}

function Save-ReleaseState {
    <# Replaces the journal atomically without storing credentials. #>
    $temporaryState = "$statePath.tmp"
    [IO.File]::WriteAllText($temporaryState, ($script:release | ConvertTo-Json -Depth 8))
    [IO.File]::Move($temporaryState, $statePath, $true)
}

function Read-ZipText {
    <# Reads a required UTF-8 package entry without extracting files. #>
    param([string]$Path, [string]$Entry)
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $item = $archive.GetEntry($Entry)
        if (!$item) { throw "Missing $Entry in $Path" }
        $reader = [IO.StreamReader]::new($item.Open())
        try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $archive.Dispose() }
}

function Assert-Packages {
    <# Checks the actual installable package identities and synchronized versions. #>
    param([string]$IdeaZip, [string]$VsZip, [string]$CodeZip, [string]$ExpectedVersion)
    [xml]$vs = Read-ZipText $VsZip 'extension.vsixmanifest'
    if ($vs.PackageManifest.Metadata.Identity.Id -ne 'FastVisualStudioBridge.27861355-A640-4497-911A-EA99B558E93F' -or
        $vs.PackageManifest.Metadata.Identity.Publisher -ne 'mmsan' -or $vs.PackageManifest.Metadata.Identity.Version -ne $ExpectedVersion) {
        throw 'Visual Studio package identity/version mismatch.'
    }
    $code = (Read-ZipText $CodeZip 'extension/package.json') | ConvertFrom-Json
    if ($code.name -ne 'scs' -or $code.publisher -ne 'mmsan' -or $code.version -ne $ExpectedVersion) {
        throw 'VS Code package identity/version mismatch.'
    }
    $outer = [IO.Compression.ZipFile]::OpenRead($IdeaZip)
    try {
        $found = $false
        foreach ($entry in $outer.Entries | Where-Object FullName -Like '*.jar') {
            $memory = [IO.MemoryStream]::new()
            try {
                $stream = $entry.Open()
                try { $stream.CopyTo($memory) } finally { $stream.Dispose() }
                $memory.Position = 0
                $jar = [IO.Compression.ZipArchive]::new($memory, [IO.Compression.ZipArchiveMode]::Read, $true)
                try {
                    $descriptor = $jar.GetEntry('META-INF/plugin.xml')
                    if (!$descriptor) { continue }
                    $reader = [IO.StreamReader]::new($descriptor.Open())
                    try { [xml]$idea = $reader.ReadToEnd() } finally { $reader.Dispose() }
                    if ($idea.'idea-plugin'.id -eq 'com.mirror.ideafocusbridge' -and $idea.'idea-plugin'.version -eq $ExpectedVersion) { $found = $true }
                } finally { $jar.Dispose() }
            } finally { $memory.Dispose() }
        }
        if (!$found) { throw 'IDEA package identity/version mismatch.' }
    } finally { $outer.Dispose() }
}

if ($Resume -and ($Version -or $PrepareOnly)) { throw '-Resume cannot be combined with -Version or -PrepareOnly.' }
if ($ConfirmSubmitted.Count -and !$Resume) { throw '-ConfirmSubmitted requires -Resume.' }

$originals = @{}
foreach ($file in @($ideaProperties, $vsManifest, $vsAssembly, $codePackage, $codeLock)) {
    $originals[$file] = [IO.File]::ReadAllBytes($file)
}
$ideaText = [IO.File]::ReadAllText($ideaProperties)
$match = [regex]::Match($ideaText, '(?m)^pluginVersion=(\d+\.\d+\.\d+)\r?$')
if (!$match.Success) { throw 'Invalid IDEA version.' }
[xml]$vsSource = [IO.File]::ReadAllText($vsManifest)
$package = [IO.File]::ReadAllText($codePackage) | ConvertFrom-Json
if ($vsSource.PackageManifest.Metadata.Identity.Id -ne 'FastVisualStudioBridge.27861355-A640-4497-911A-EA99B558E93F' -or
    $vsSource.PackageManifest.Metadata.Identity.Publisher -ne 'mmsan' -or $package.name -ne 'scs' -or $package.publisher -ne 'mmsan') {
    throw 'Unexpected source plugin identity/publisher. Refusing to modify or publish.'
}
$versions = @($match.Groups[1].Value, $vsSource.PackageManifest.Metadata.Identity.Version, $package.version)
$highest = $versions | ForEach-Object { [version]$_ } | Sort-Object -Descending | Select-Object -First 1
$nextVersion = if ($Version) { $Version } else { '{0}.{1}.{2}' -f $highest.Major, $highest.Minor, ($highest.Build + 1) }
if (!$Resume -and [version]$nextVersion -le $highest) { throw 'The release version must exceed every current plugin version. Use -Resume for an interrupted upload.' }
if ($Resume) {
    $script:release = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    $nextVersion = $release.version
}
Write-Host "Current: IDEA=$($versions[0]), Visual Studio=$($versions[1]), VS Code=$($versions[2])"
Write-Host "Release: $nextVersion; order: JetBrains -> Visual Studio -> VS Code"
if ($Plan) {
    Write-Host 'Plan only: no versions changed, packages built, or uploads performed.'
    if ($Resume) { $release.uploads | Format-Table platform, status }
    exit 0
}

$npm = (Get-Command npm.cmd -ErrorAction Stop).Source
$vsixPublisher = $null
if (!$PrepareOnly) {
    $jbToken = $env:JETBRAINS_MARKETPLACE_TOKEN
    $msToken = $env:VS_MARKETPLACE_TOKEN
    if ((!$jbToken -or !$msToken) -and (Test-Path $credentialPath)) {
        $credentials = Import-Clixml -LiteralPath $credentialPath
        if (!$jbToken) { $jbToken = [Net.NetworkCredential]::new('', $credentials.JetBrains).Password }
        if (!$msToken) { $msToken = [Net.NetworkCredential]::new('', $credentials.Microsoft).Password }
    }
    $needed = if ($Resume) { @($release.uploads | Where-Object { $_.status -ne 'Submitted' -and $_.platform -notin $ConfirmSubmitted } | ForEach-Object platform) } else { @('JetBrains', 'VisualStudio', 'VSCode') }
    if ('JetBrains' -in $needed -and !$jbToken) { throw 'Run -ConfigureCredentials or set JETBRAINS_MARKETPLACE_TOKEN before publishing; -PrepareOnly builds without uploading.' }
    if (('VisualStudio' -in $needed -or 'VSCode' -in $needed) -and !$msToken) { throw 'Run -ConfigureCredentials or set VS_MARKETPLACE_TOKEN before publishing; -PrepareOnly builds without uploading.' }
    if ('VisualStudio' -in $needed) {
        $vswhere = Join-Path ${env:ProgramFiles(x86)} 'Microsoft Visual Studio/Installer/vswhere.exe'
        if (Test-Path $vswhere) { $vsixPublisher = & $vswhere -latest -products * -find 'VSSDK/VisualStudioIntegration/Tools/Bin/VsixPublisher.exe' | Select-Object -First 1 }
        if (!$vsixPublisher) {
            $vsixPublisher = Get-ChildItem "$env:USERPROFILE/.nuget/packages/microsoft.vssdk.buildtools/*/tools/vssdk/bin/VsixPublisher.exe" -ErrorAction SilentlyContinue |
                Sort-Object FullName -Descending | Select-Object -First 1 -ExpandProperty FullName
        }
        if (!$vsixPublisher) { throw 'VsixPublisher.exe was not found. Install the Visual Studio extension development SDK.' }
    }
}

New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
$lock = [IO.File]::Open((Join-Path $releaseRoot 'release.lock'), 'OpenOrCreate', 'ReadWrite', 'None')
try {
    if ($Resume) {
        $script:release = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
        if ($release.version -ne $nextVersion) { throw 'The release changed during preflight. Run -Resume again.' }
    }
    if (!$Resume) {
        foreach ($file in $originals.Keys) {
            if ([Convert]::ToBase64String([IO.File]::ReadAllBytes($file)) -ne [Convert]::ToBase64String($originals[$file])) {
                throw 'Source versions changed during preflight. Run the command again.'
            }
        }
        if (Test-Path $statePath) {
            $previous = Get-Content $statePath -Raw | ConvertFrom-Json
            if (@($previous.uploads | Where-Object status -NE 'Submitted').Count) { throw 'An unfinished release exists. Use -Resume before starting a new version.' }
        }
        $directory = Join-Path $releaseRoot $nextVersion
        if (Test-Path $directory) { throw "Release directory already exists: $directory" }
        $createdDirectory = $false
        try {
            [IO.File]::WriteAllText($ideaProperties, [regex]::Replace($ideaText, '(?m)^pluginVersion=[^\r\n]+', "pluginVersion=$nextVersion"))
            $text = [IO.File]::ReadAllText($vsManifest)
            [IO.File]::WriteAllText($vsManifest, [regex]::Replace($text, '(?<=<Identity Id="FastVisualStudioBridge\.27861355-A640-4497-911A-EA99B558E93F" Version=")[^"]+', $nextVersion))
            $text = [IO.File]::ReadAllText($vsAssembly)
            [IO.File]::WriteAllText($vsAssembly, [regex]::Replace($text, '(?<=Assembly(?:File)?Version\(")[^"]+', "$nextVersion.0"))
            foreach ($file in @($codePackage, $codeLock)) {
                $text = [IO.File]::ReadAllText($file)
                # Only the package and lockfile root versions occur before the first dependency entry.
                $limit = if ($file -eq $codeLock) { $text.IndexOf('"node_modules/') } else { $text.Length }
                if ($limit -lt 0) { $limit = $text.Length }
                $head = [regex]::Replace($text.Substring(0, $limit), '("version"\s*:\s*")[^"]+(")', "`${1}$nextVersion`${2}")
                [IO.File]::WriteAllText($file, $head + $text.Substring($limit))
            }
            Push-Location $PSScriptRoot
            try { & ./build-plugin.ps1 -VerifyCompatibility } finally { Pop-Location }
            Push-Location $VisualStudioRoot
            try { & ./build-plugin.ps1 -UpdateChannel Official } finally { Pop-Location }
            Push-Location $VSCodeRoot
            try {
                Invoke-ReleaseCommand (Get-Command node.exe).Source @('--check', 'extension.js')
                Invoke-ReleaseCommand (Get-Command node.exe).Source @('--check', 'fastBridge.js')
                & ./publish-local-plugin.ps1 -NoVersionBump -SkipInstall
                Invoke-ReleaseCommand $npm @('ls', '--omit=dev')
            } finally { Pop-Location }
            $ideaZip = Join-Path $PSScriptRoot "build/distributions/fast-idea-bridge-$nextVersion.zip"
            $vsZip = Join-Path $VisualStudioRoot 'dist/FastVisualStudioBridge.vsix'
            $codeZip = Join-Path $VSCodeRoot "scs-$nextVersion.vsix"
            Assert-Packages $ideaZip $vsZip $codeZip $nextVersion
            New-Item -ItemType Directory -Path $directory | Out-Null
            $createdDirectory = $true
            Copy-Item $ideaZip,$vsZip,$codeZip -Destination $directory
            Copy-Item (Join-Path $VisualStudioRoot 'README.md') (Join-Path $directory 'overview.md')
            $manifest = @{ categories = @('coding'); identity = @{ internalName = 'FastVisualStudioBridge' }; overview = 'overview.md'; priceCategory = 'free'; publisher = 'mmsan'; private = $false; qna = $true; repo = 'https://github.com/mmsan/fast-visual-studio' }
            $manifest | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $directory 'vs-publish.json') -Encoding utf8
            $script:release = [pscustomobject]@{ version = $nextVersion; directory = $directory; uploads = @(); files = @() }
            $artifacts = @($ideaZip, $vsZip, $codeZip)
            $platforms = @('JetBrains', 'VisualStudio', 'VSCode')
            for ($i = 0; $i -lt 3; $i++) {
                $release.uploads += [pscustomobject]@{ platform = $platforms[$i]; artifact = [IO.Path]::GetFileName($artifacts[$i]); status = 'Pending' }
            }
            foreach ($file in Get-ChildItem $directory -File) {
                $release.files += [pscustomobject]@{ name = $file.Name; sha256 = (Get-FileHash $file.FullName -Algorithm SHA256).Hash }
            }
            Save-ReleaseState
        } catch {
            foreach ($file in $originals.Keys) { [IO.File]::WriteAllBytes($file, $originals[$file]) }
            if ($createdDirectory) {
                $resolvedDirectory = [IO.Path]::GetFullPath($directory)
                $expectedParent = [IO.Path]::GetFullPath($releaseRoot)
                if ([IO.Path]::GetDirectoryName($resolvedDirectory) -eq $expectedParent) {
                    Remove-Item -LiteralPath $resolvedDirectory -Recurse -Force
                }
            }
            throw
        }
    }
    foreach ($file in $release.files) {
        if ((Get-FileHash (Join-Path $release.directory $file.name) -Algorithm SHA256).Hash -ne $file.sha256) { throw "Release file changed: $($file.name). Refusing to upload." }
    }
    if ($PrepareOnly) { Write-Host "Packages verified and saved. Publish with: .\publish-marketplace.cmd -Resume"; exit 0 }
    foreach ($upload in $release.uploads) {
        if ($upload.platform -in $ConfirmSubmitted) {
            if ($upload.status -ne 'Uploading') { throw '-ConfirmSubmitted is only valid for an interrupted upload with unknown outcome.' }
            $upload.status = 'Submitted'
            Save-ReleaseState
        }
        if ($upload.status -eq 'Submitted') { continue }
        if ($upload.status -eq 'Uploading') { throw "$($upload.platform) outcome is unknown. Check the marketplace first. If accepted, use -Resume -ConfirmSubmitted $($upload.platform); otherwise set its journal status to Pending and resume." }
        $artifact = Join-Path $release.directory $upload.artifact
        $upload.status = 'Uploading'
        Save-ReleaseState
        Write-Host "Submitting $($upload.platform) $($release.version)..."
        switch ($upload.platform) {
            'JetBrains' {
                $response = Invoke-RestMethod -Method Post -Uri 'https://plugins.jetbrains.com/api/updates/upload' -Headers @{ Authorization = "Bearer $jbToken" } -Form @{ pluginId = '35046'; file = Get-Item $artifact; channel = '' } -TimeoutSec 300
                if (!$response) { throw 'JetBrains returned an empty response. Check the marketplace before resuming.' }
            }
            'VisualStudio' {
                # VsixPublisher only accepts PATs via its argument or its own saved login.
                $output = & $vsixPublisher publish -payload $artifact -publishManifest (Join-Path $release.directory 'vs-publish.json') -personalAccessToken $msToken 2>&1
                $publishExit = $LASTEXITCODE
                foreach ($line in $output) { Write-Host ("$line".Replace($msToken, '[REDACTED]')) }
                if ($publishExit -ne 0) { throw "VsixPublisher failed (exit $publishExit). Check the marketplace before resuming." }
            }
            'VSCode' {
                $previousPat = $env:VSCE_PAT
                Push-Location $VSCodeRoot
                try {
                    $env:VSCE_PAT = $msToken
                    $npmCache = Join-Path ([IO.Path]::GetTempPath()) 'fast-vscode-npm-cache'
                    Invoke-ReleaseCommand $npm @('--cache', $npmCache, 'exec', '--yes', '--', '@vscode/vsce', 'publish', '--packagePath', $artifact)
                } finally {
                    $env:VSCE_PAT = $previousPat
                    Pop-Location
                }
            }
        }
        $upload.status = 'Submitted'
        Save-ReleaseState
    }
    Write-Host "All three $($release.version) uploads were accepted. Marketplace validation/moderation may still be pending."
    Write-Host 'Version changes remain in the source repositories; review and commit them normally.'
} finally { $lock.Dispose() }
