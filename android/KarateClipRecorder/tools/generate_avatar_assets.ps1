param(
    [string]$SourceDirectory = (Join-Path $PSScriptRoot '..\app\src\main\avatar-sources'),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\app\src\main\assets\avatars')
)

$ErrorActionPreference = 'Stop'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

function Get-AvatarRole([int]$Red, [int]$Green, [int]$Blue) {
    if ($Red -gt 100 -and $Blue -gt 70 -and $Red -gt ($Green * 1.35)) { return 'background' }
    if ($Red -gt 170 -and $Green -gt 85 -and $Green -lt 205 -and $Blue -lt 65) { return 'skin' }
    if ($Green -gt 70 -and $Blue -lt 90 -and $Green -gt ($Red * 1.12)) { return 'belt' }
    if ([Math]::Max($Red, [Math]::Max($Green, $Blue)) -lt 25) { return 'outline' }
    if ($Blue -gt ($Red * 1.25) -and $Blue -gt ($Green * 1.15)) { return 'hair' }
    if ([Math]::Max($Red, [Math]::Max($Green, $Blue)) -lt 100) { return 'hair' }
    return 'fixed'
}

Get-ChildItem -LiteralPath $SourceDirectory -Filter 'avatar_*.svg' | Sort-Object Name | ForEach-Object {
    $svg = Get-Content -LiteralPath $_.FullName -Raw
    $viewBoxMatch = [regex]::Match($svg, 'viewBox="0 0 ([0-9.]+) ([0-9.]+)"')
    if (-not $viewBoxMatch.Success) { throw "Missing viewBox in $($_.Name)" }

    $pathMatches = [regex]::Matches(
        $svg,
        '<path d="([^"]+)" fill="rgb\(([0-9]+),([0-9]+),([0-9]+)\)"\s*/>',
        [Text.RegularExpressions.RegexOptions]::Singleline
    )
    if ($pathMatches.Count -eq 0) { throw "No supported paths in $($_.Name)" }

    $paths = foreach ($match in $pathMatches) {
        $red = [int]$match.Groups[2].Value
        $green = [int]$match.Groups[3].Value
        $blue = [int]$match.Groups[4].Value
        $role = Get-AvatarRole $red $green $blue
        [pscustomobject]@{
            Data = $match.Groups[1].Value
            Red = $red
            Green = $green
            Blue = $blue
            Role = $role
            Luminance = (0.2126 * $red) + (0.7152 * $green) + (0.0722 * $blue)
        }
    }

    $roleMaximums = @{}
    foreach ($role in @('skin', 'hair', 'belt', 'background')) {
        $values = @($paths | Where-Object Role -eq $role | ForEach-Object Luminance)
        $roleMaximums[$role] = if ($values.Count -gt 0) { ($values | Measure-Object -Maximum).Maximum } else { 255.0 }
    }

    $builder = [Text.StringBuilder]::new()
    [void]$builder.AppendLine('<?xml version="1.0" encoding="utf-8"?>')
    [void]$builder.AppendLine(('<avatar width="{0}" height="{1}">' -f $viewBoxMatch.Groups[1].Value, $viewBoxMatch.Groups[2].Value))
    foreach ($path in $paths) {
        $tone = 1.0
        if ($path.Role -in @('skin', 'hair', 'belt', 'background')) {
            $tone = 0.55 + (0.55 * ($path.Luminance / $roleMaximums[$path.Role]))
        }
        $escapedData = [Security.SecurityElement]::Escape($path.Data)
        $toneText = $tone.ToString('0.000', [Globalization.CultureInfo]::InvariantCulture)
        [void]$builder.AppendLine(('  <path role="{0}" tone="{1}" red="{2}" green="{3}" blue="{4}" data="{5}" />' -f $path.Role, $toneText, $path.Red, $path.Green, $path.Blue, $escapedData))
    }
    [void]$builder.AppendLine('</avatar>')
    $destination = Join-Path $OutputDirectory ($_.BaseName + '.xml')
    [IO.File]::WriteAllText($destination, $builder.ToString(), [Text.UTF8Encoding]::new($false))
}

Write-Output "Generated avatar models in $OutputDirectory"
