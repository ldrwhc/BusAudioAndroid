param(
    [string]$BbDir = $PSScriptRoot,
    [string]$Key = "BusAnnouncement@2026",
    [ValidateSet("split", "combined")]
    [string]$PackMode = "split"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

function Ensure-Dir([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path | Out-Null
    }
}

function Remove-DirIfExists([string]$Path) {
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
}

function Protect-Bytes {
    param([byte[]]$Data, [byte[]]$KeyBytes, [byte[]]$Nonce)
    $result = New-Object byte[] $Data.Length
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $offset = 0
        [uint32]$counter = 0
        while ($offset -lt $Data.Length) {
            $counterBytes = [BitConverter]::GetBytes($counter)
            $seed = New-Object byte[] ($KeyBytes.Length + $Nonce.Length + 4)
            [Array]::Copy($KeyBytes, 0, $seed, 0, $KeyBytes.Length)
            [Array]::Copy($Nonce, 0, $seed, $KeyBytes.Length, $Nonce.Length)
            [Array]::Copy($counterBytes, 0, $seed, $KeyBytes.Length + $Nonce.Length, 4)
            $block = $sha.ComputeHash($seed)
            $n = [Math]::Min($block.Length, $Data.Length - $offset)
            for ($i = 0; $i -lt $n; $i++) {
                $result[$offset + $i] = $Data[$offset + $i] -bxor $block[$i]
            }
            $offset += $n
            $counter++
        }
    } finally {
        $sha.Dispose()
    }
    return ,$result
}

function Get-SafePakName {
    param([string]$SourceName, [hashtable]$Used)
    if ([string]::IsNullOrWhiteSpace($SourceName)) { $SourceName = "pack" }
    $base = [System.Text.RegularExpressions.Regex]::Replace($SourceName, '[^A-Za-z0-9._-]', '_').Trim('_', '.', ' ')
    if ([string]::IsNullOrWhiteSpace($base)) { $base = "pack" }
    $sha = [System.Security.Cryptography.SHA1]::Create()
    try {
        $hash = ($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($SourceName)) | ForEach-Object { $_.ToString("x2") }) -join ""
    } finally {
        $sha.Dispose()
    }
    $short = $hash.Substring(0, 8)
    $name = "${base}_${short}.pak"
    $idx = 1
    while ($Used.ContainsKey($name.ToLower())) {
        $name = "${base}_${short}_$idx.pak"
        $idx++
    }
    $Used[$name.ToLower()] = $true
    return $name
}

function Get-SafeAliasFileName {
    param([string]$RelativePath, [hashtable]$Used)
    $origName = [System.IO.Path]::GetFileName($RelativePath)
    $ext = [System.IO.Path]::GetExtension($origName).ToLower()
    $stem = [System.IO.Path]::GetFileNameWithoutExtension($origName)
    $safeStem = [System.Text.RegularExpressions.Regex]::Replace($stem, '[^A-Za-z0-9._-]', '_').Trim('_', '.', ' ')
    if ([string]::IsNullOrWhiteSpace($safeStem)) { $safeStem = "audio" }
    $sha = [System.Security.Cryptography.SHA1]::Create()
    try {
        $hash = ($sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($RelativePath)) | ForEach-Object { $_.ToString("x2") }) -join ""
    } finally {
        $sha.Dispose()
    }
    $short = $hash.Substring(0, 10)
    $name = "${safeStem}_${short}${ext}"
    $idx = 1
    while ($Used.ContainsKey($name.ToLower())) {
        $name = "${safeStem}_${short}_$idx${ext}"
        $idx++
    }
    $Used[$name.ToLower()] = $true
    return $name
}

function Get-PackKind([string]$SourceName) {
    if ([string]::IsNullOrWhiteSpace($SourceName)) { return "misc" }
    $n = $SourceName.ToLower()
    if ($n -like "00concateng*") { return "concat_eng" }
    if ($n -like "00concat*") { return "concat" }
    if ($n -like "template*") { return "template" }
    if ($n -like "00prompt*" -or $n -like "tips*" -or $n -like "*提示*" -or $n -like "*发车通知*") { return "prompt" }
    return "misc"
}

function Build-EncryptedPak {
    param([string]$StagingDir, [string]$PakPath, [byte[]]$KeyBytes)
    [byte[]]$magic = 0x42,0x55,0x53,0x41,0x55,0x44,0x31,0x00 # BUSAUD1\0
    $zipPath = Join-Path ([System.IO.Path]::GetTempPath()) ("android_pack_" + [Guid]::NewGuid().ToString("N") + ".zip")
    try {
        if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
        Compress-Archive -Path (Join-Path $StagingDir "*") -DestinationPath $zipPath -Force
        [byte[]]$plain = [System.IO.File]::ReadAllBytes($zipPath)
        [byte[]]$nonce = New-Object byte[] 16
        $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($nonce) } finally { $rng.Dispose() }
        [byte[]]$cipher = Protect-Bytes -Data $plain -KeyBytes $KeyBytes -Nonce $nonce
        [byte[]]$all = New-Object byte[] ($magic.Length + $nonce.Length + $cipher.Length)
        [Array]::Copy($magic, 0, $all, 0, $magic.Length)
        [Array]::Copy($nonce, 0, $all, $magic.Length, $nonce.Length)
        [Array]::Copy($cipher, 0, $all, $magic.Length + $nonce.Length, $cipher.Length)
        [System.IO.File]::WriteAllBytes($PakPath, $all)
    } finally {
        if (Test-Path -LiteralPath $zipPath) { Remove-Item -LiteralPath $zipPath -Force }
    }
}

$bb = (Resolve-Path -LiteralPath $BbDir).Path
$root = (Resolve-Path -LiteralPath (Join-Path $bb "..")).Path
$assetsRoot = Join-Path $bb "app\src\main\assets\payload"
$packsOut = Join-Path $assetsRoot "packs"
$seedCfgOut = Join-Path $assetsRoot "seed_config.zip"
$seedLinesOut = Join-Path $assetsRoot "seed_lines.zip"

Remove-DirIfExists $assetsRoot
Ensure-Dir $packsOut

$audioExt = @(".mp3", ".wav", ".m4a", ".ogg", ".flac", ".aac", ".wma")
$sourceDirs = Get-ChildItem -LiteralPath $root -Directory | Where-Object {
    $_.Name -like "00concat*" -or
    $_.Name -like "00prompt*" -or
    $_.Name -like "tips*" -or
    $_.Name -like "template*" -or
    $_.Name -like "*提示*" -or
    $_.Name -like "*发车通知*"
}

$enterModeName = ([string][char]0x8FDB)+[char]0x5165+[char]0x516C+[char]0x4EA4+[char]0x7EBF+[char]0x8DEF+[char]0x9009+[char]0x62E9+[char]0x6A21+[char]0x5F0F
$switchLineName = ([string][char]0x5207)+[char]0x6362+[char]0x7EBF+[char]0x8DEF+[char]0x4E3A
$standalonePromptNames = @(
    "$enterModeName.wav",
    "$enterModeName.mp3",
    "$switchLineName.wav",
    "$switchLineName.mp3"
)
$standalonePromptFiles = @()
foreach ($n in $standalonePromptNames) {
    $cand = Join-Path $root $n
    if (Test-Path -LiteralPath $cand) {
        $standalonePromptFiles += (Get-Item -LiteralPath $cand)
    }
}

if (($sourceDirs.Count -eq 0) -and ($standalonePromptFiles.Count -eq 0)) {
    throw "No source dirs found. Need 00concat*/00concatEng*/template* or prompt resources."
}

$generatedPaks = New-Object System.Collections.Generic.List[string]
$usedPakNames = @{}
$packageAliasMaps = @{}
$packageKinds = @{}
$keyBytes = [System.Text.Encoding]::UTF8.GetBytes($Key)

if ($PackMode -eq "combined") {
    $staging = Join-Path ([System.IO.Path]::GetTempPath()) ("android_pack_stage_all_" + [Guid]::NewGuid().ToString("N"))
    Ensure-Dir $staging
    $aliasUsed = @{}
    $aliasMap = [ordered]@{}
    try {
        foreach ($dir in $sourceDirs) {
            $audioFiles = Get-ChildItem -LiteralPath $dir.FullName -Recurse -File | Where-Object { $audioExt -contains $_.Extension.ToLower() }
            foreach ($file in $audioFiles) {
                $relative = $file.FullName.Substring($dir.FullName.Length).TrimStart('\','/')
                $alias = Get-SafeAliasFileName -RelativePath ((Join-Path $dir.Name $relative).Replace('\','/')) -Used $aliasUsed
                Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $staging $alias) -Force
                $aliasMap[$alias] = $file.Name
            }
        }
        $pakName = Get-SafePakName -SourceName "all_audio" -Used $usedPakNames
        Build-EncryptedPak -StagingDir $staging -PakPath (Join-Path $packsOut $pakName) -KeyBytes $keyBytes
        $generatedPaks.Add($pakName) | Out-Null
        $packageAliasMaps[$pakName] = $aliasMap
        $packageKinds[$pakName] = "mixed"
    } finally {
        Remove-DirIfExists $staging
    }
} else {
    foreach ($dir in $sourceDirs) {
        $audioFiles = Get-ChildItem -LiteralPath $dir.FullName -Recurse -File | Where-Object { $audioExt -contains $_.Extension.ToLower() }
        if (-not $audioFiles) { continue }
        $staging = Join-Path ([System.IO.Path]::GetTempPath()) ("android_pack_stage_" + [Guid]::NewGuid().ToString("N"))
        Ensure-Dir $staging
        $aliasUsed = @{}
        $aliasMap = [ordered]@{}
        try {
            foreach ($file in $audioFiles) {
                $relative = $file.FullName.Substring($dir.FullName.Length).TrimStart('\','/')
                $alias = Get-SafeAliasFileName -RelativePath $relative -Used $aliasUsed
                Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $staging $alias) -Force
                $aliasMap[$alias] = $file.Name
            }
            $pakName = Get-SafePakName -SourceName $dir.Name -Used $usedPakNames
            Build-EncryptedPak -StagingDir $staging -PakPath (Join-Path $packsOut $pakName) -KeyBytes $keyBytes
            $generatedPaks.Add($pakName) | Out-Null
            $packageAliasMaps[$pakName] = $aliasMap
            $packageKinds[$pakName] = Get-PackKind -SourceName $dir.Name
            Write-Host "[OK] $($dir.Name) -> $pakName"
        } finally {
            Remove-DirIfExists $staging
        }
    }
}

if ($standalonePromptFiles.Count -gt 0) {
    $stagingPrompt = Join-Path ([System.IO.Path]::GetTempPath()) ("android_pack_stage_prompt_" + [Guid]::NewGuid().ToString("N"))
    Ensure-Dir $stagingPrompt
    $aliasUsedPrompt = @{}
    $aliasMapPrompt = [ordered]@{}
    try {
        foreach ($file in $standalonePromptFiles) {
            $alias = Get-SafeAliasFileName -RelativePath $file.Name -Used $aliasUsedPrompt
            Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $stagingPrompt $alias) -Force
            $aliasMapPrompt[$alias] = $file.Name
        }
        $pakName = Get-SafePakName -SourceName "standalone_prompt" -Used $usedPakNames
        Build-EncryptedPak -StagingDir $stagingPrompt -PakPath (Join-Path $packsOut $pakName) -KeyBytes $keyBytes
        $generatedPaks.Add($pakName) | Out-Null
        $packageAliasMaps[$pakName] = $aliasMapPrompt
        $packageKinds[$pakName] = "prompt"
        Write-Host "[OK] standalone prompt -> $pakName"
    } finally {
        Remove-DirIfExists $stagingPrompt
    }
}

$configSeedStage = Join-Path ([System.IO.Path]::GetTempPath()) ("android_seed_cfg_" + [Guid]::NewGuid().ToString("N"))
$linesSeedStage = Join-Path ([System.IO.Path]::GetTempPath()) ("android_seed_lines_" + [Guid]::NewGuid().ToString("N"))
Ensure-Dir $configSeedStage
Ensure-Dir $linesSeedStage

try {
    $cfgSrc = Join-Path $root "00config"
    if (Test-Path -LiteralPath $cfgSrc) {
        Get-ChildItem -LiteralPath $cfgSrc -File -Filter "*.json" | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $configSeedStage $_.Name) -Force
        }
    }

    $aliasesObj = [ordered]@{}
    foreach ($pakName in ($packageAliasMaps.Keys | Sort-Object)) {
        $inner = [ordered]@{}
        foreach ($alias in ($packageAliasMaps[$pakName].Keys | Sort-Object)) {
            $inner[$alias] = [string]$packageAliasMaps[$pakName][$alias]
        }
        $aliasesObj[$pakName] = $inner
    }
    $kindsObj = [ordered]@{}
    foreach ($pakName in ($packageKinds.Keys | Sort-Object)) {
        $kindsObj[$pakName] = [string]$packageKinds[$pakName]
    }
    $manifest = [ordered]@{
        key = $Key
        packages = $generatedPaks
        aliases = $aliasesObj
        kinds = $kindsObj
    }
    $manifest | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $configSeedStage "pack_manifest.json") -Encoding UTF8

    if (Test-Path -LiteralPath $seedCfgOut) { Remove-Item -LiteralPath $seedCfgOut -Force }
    Compress-Archive -Path (Join-Path $configSeedStage "*") -DestinationPath $seedCfgOut -Force

    $lineSrc = Join-Path $root "00lines"
    if (Test-Path -LiteralPath $lineSrc) {
        Get-ChildItem -LiteralPath $lineSrc -File | Where-Object {
            $_.Extension -ieq ".xlsx" -or $_.Extension -ieq ".txt"
        } | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $linesSeedStage $_.Name) -Force
        }
    }
    if (Test-Path -LiteralPath $seedLinesOut) { Remove-Item -LiteralPath $seedLinesOut -Force }
    Compress-Archive -Path (Join-Path $linesSeedStage "*") -DestinationPath $seedLinesOut -Force
}
finally {
    Remove-DirIfExists $configSeedStage
    Remove-DirIfExists $linesSeedStage
}

Write-Host "[DONE] payload generated at $assetsRoot"
