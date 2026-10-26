#requires -Version 7.0
<#
.SYNOPSIS
Creates a consistent SQL backup of this project's dedicated MySQL schema.
.DESCRIPTION
Run explicitly after deployment. No backup or container command runs by adding this file.
Passwords are read inside the db container from its existing MYSQL_PASSWORD environment.
Avoid running schema migrations while a backup is in progress.
#>
[CmdletBinding()]
param(
    [string]$BackupDirectory = (Join-Path (Split-Path -Parent $PSScriptRoot) '.tools/backups'),
    [string]$ComposeFile = (Join-Path (Split-Path -Parent $PSScriptRoot) 'compose.yml'),
    [string]$EnvFile,
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{0,62}$')]
    [string]$ProjectName = 'global-news-flash'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$dockerCommand = (Get-Command docker -CommandType Application -ErrorAction Stop).Source
$composePath = (Resolve-Path -LiteralPath $ComposeFile -ErrorAction Stop).ProviderPath
$backupRoot = [IO.Path]::GetFullPath($BackupDirectory)
[IO.Directory]::CreateDirectory($backupRoot) | Out-Null
$stamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ')
$name = 'global-news-flash-' + $stamp + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8) + '.sql'
$backupPath = Join-Path $backupRoot $name
$partialPath = $backupPath + '.partial'

$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = $dockerCommand
$info.WorkingDirectory = $workspace
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
foreach ($argument in @('compose', '--project-directory', $workspace, '--project-name', $ProjectName, '--file', $composePath)) {
    $info.ArgumentList.Add($argument)
}
if ($EnvFile) {
    $info.ArgumentList.Add('--env-file')
    $info.ArgumentList.Add((Resolve-Path -LiteralPath $EnvFile -ErrorAction Stop).ProviderPath)
}
# The host command contains only references to container variables, never the password value.
$dumpCommand = 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysqldump --user="$MYSQL_USER" --single-transaction --quick --skip-lock-tables --no-tablespaces --set-gtid-purged=OFF --default-character-set=utf8mb4 --hex-blob "$MYSQL_DATABASE"'
foreach ($argument in @('exec', '-T', 'db', 'sh', '-c', $dumpCommand)) { $info.ArgumentList.Add($argument) }

$process = [Diagnostics.Process]::new()
$process.StartInfo = $info
$stream = $null
$completed = $false
try {
    $stream = [IO.File]::Open($partialPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    if (-not $process.Start()) { throw 'Could not start the backup command.' }
    $errorTask = $process.StandardError.ReadToEndAsync()
    # Preserve the dump bytes; PowerShell text redirection can alter SQL encoding.
    $process.StandardOutput.BaseStream.CopyTo($stream)
    $process.WaitForExit()
    $null = $errorTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw ('MySQL backup failed; docker compose exit code ' + $process.ExitCode + '. No complete backup was produced.') }
    $stream.Flush()
    if ($stream.Length -eq 0) { throw 'MySQL returned an empty backup.' }
    $stream.Dispose()
    $stream = $null
    Move-Item -LiteralPath $partialPath -Destination $backupPath -ErrorAction Stop
    $checksum = (Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash.ToLowerInvariant()
    [IO.File]::WriteAllText($backupPath + '.sha256', $checksum + '  ' + $name + "`n", [Text.UTF8Encoding]::new($false))
    $completed = $true
    Write-Output $backupPath
} finally {
    if ($stream) { $stream.Dispose() }
    try { if (-not $process.HasExited) { $process.Kill($true); $process.WaitForExit() } } catch { }
    $process.Dispose()
    if (-not $completed -and (Test-Path -LiteralPath $partialPath)) {
        # Delete only this generated incomplete file, never a directory or an existing backup.
        Remove-Item -LiteralPath $partialPath -ErrorAction SilentlyContinue
    }
}
