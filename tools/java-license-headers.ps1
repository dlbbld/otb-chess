param(
  [switch] $Check,
  [switch] $Fix
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if (($Check -and $Fix) -or (-not $Check -and -not $Fix)) {
  Write-Error "Use exactly one mode: -Check or -Fix."
}

$expectedHeader = @"
// Copyright (C) 2026 Daniel Baechli
// SPDX-License-Identifier: GPL-3.0-only

"@
$expectedHeader = $expectedHeader -replace "`r`n", "`n"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$sourceRoots = @(
  Join-Path $repoRoot "src/main/java"
  Join-Path $repoRoot "src/test/java"
)

function Convert-ToRepoRelativePath {
  param([string] $Path)

  return [System.IO.Path]::GetRelativePath($repoRoot, $Path).Replace("\", "/")
}

function Remove-LeadingLicenseHeader {
  param([string] $Content)

  $lines = $Content -split "`n", -1
  if ($lines.Count -eq 0) {
    return $Content
  }

  $firstLine = $lines[0]
  if ($firstLine -notmatch "^//\s*(Copyright|SPDX-License-Identifier)") {
    return $Content
  }

  $index = 0
  while ($index -lt $lines.Count) {
    $line = $lines[$index]
    if ($line -match "^//\s*(Copyright|SPDX-License-Identifier)") {
      $index++
      continue
    }
    if ($line -eq "") {
      $index++
      break
    }
    break
  }

  if ($index -ge $lines.Count) {
    return ""
  }

  return ($lines[$index..($lines.Count - 1)] -join "`n")
}

$javaFiles = foreach ($sourceRoot in $sourceRoots) {
  if (Test-Path $sourceRoot) {
    Get-ChildItem -Path $sourceRoot -Recurse -File -Filter "*.java"
  }
}

$invalidFiles = New-Object System.Collections.Generic.List[string]
$changedCount = 0
$utf8NoBom = [System.Text.UTF8Encoding]::new($false)

foreach ($file in $javaFiles) {
  $path = $file.FullName
  $content = [System.IO.File]::ReadAllText($path)
  $content = $content -replace "`r`n", "`n"
  $content = $content -replace "`r", "`n"
  $content = $content.TrimStart([char]0xFEFF)

  if ($content.StartsWith($expectedHeader, [System.StringComparison]::Ordinal)) {
    continue
  }

  $invalidFiles.Add((Convert-ToRepoRelativePath $path))

  if ($Fix) {
    $withoutHeader = Remove-LeadingLicenseHeader $content
    $fixedContent = $expectedHeader + $withoutHeader
    [System.IO.File]::WriteAllText($path, $fixedContent, $utf8NoBom)
    $changedCount++
  }
}

if ($Fix) {
  Write-Host "Fixed $changedCount Java file header(s)."
}

if ($invalidFiles.Count -gt 0) {
  if ($Check) {
    Write-Host "Java files with missing or incorrect license headers:"
    $invalidFiles | ForEach-Object { Write-Host "  $_" }
    exit 1
  }
}

Write-Host "Java license headers are exact."
