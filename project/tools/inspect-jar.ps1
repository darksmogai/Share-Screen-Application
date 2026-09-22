param(
    [Parameter(Mandatory = $true)][string]$Name,
    [string]$Pattern = '.*'
)
Add-Type -AssemblyName System.IO.Compression.FileSystem
$repo = Join-Path $HOME '.m2\repository'
$jar = Get-ChildItem -Path $repo -Recurse -Filter $Name -File |
    Where-Object { $_.FullName -notmatch '\.sha1$' } |
    Select-Object -First 1
if (-not $jar) { Write-Error "not found: $Name"; exit 2 }
Write-Output "JAR: $($jar.FullName)"
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
try {
    $zip.Entries |
        Where-Object { $_.FullName -match $Pattern } |
        Sort-Object -Property FullName |
        ForEach-Object { "{0,10}  {1}" -f $_.Length, $_.FullName }
}
finally {
    $zip.Dispose()
}

