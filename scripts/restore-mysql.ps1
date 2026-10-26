#requires -Version 7.0
<#
.SYNOPSIS
Explicitly restores a SQL backup into the dedicated global_news_flash schema.
.DESCRIPTION
This replaces existing tables. Stop this project's app service before invoking the script.
The app is never started automatically; a successful import also forces publication paused.
After restoring, reconcile messages sent after the backup before enabling Telegram delivery.
.EXAMPLE
./scripts/restore-mysql.ps1 -BackupFile ./path/to/global-news-flash-backup.sql
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$BackupFile,
    [string]$ComposeFile = (Join-Path (Split-Path -Parent $PSScriptRoot) 'compose.yml'),
    [string]$EnvFile,
    [ValidatePattern('^[a-z0-9][a-z0-9_-]{0,62}$')]
    [string]$ProjectName = 'global-news-flash'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$dockerCommand = (Get-Command docker.exe -CommandType Application -ErrorAction Stop | Select-Object -First 1).Source
$composePath = (Resolve-Path -LiteralPath $ComposeFile -ErrorAction Stop).ProviderPath
$backupPath = (Resolve-Path -LiteralPath $BackupFile -ErrorAction Stop).ProviderPath
$backupItem = Get-Item -LiteralPath $backupPath
if ($backupItem.PSIsContainer -or $backupItem.Length -eq 0 -or $backupItem.Extension -ine '.sql') {
    throw 'BackupFile must be a nonempty, completed .sql file.'
}
if (Test-Path -LiteralPath ($backupPath + '.sha256')) {
    $checksumText = [IO.File]::ReadAllText($backupPath + '.sha256').Trim()
    if ($checksumText -notmatch '^([a-fA-F0-9]{64})(?:\s|$)') { throw 'Invalid backup checksum file.' }
    $expectedChecksum = $Matches[1]
    if ((Get-FileHash -LiteralPath $backupPath -Algorithm SHA256).Hash -ine $expectedChecksum) {
        throw 'Backup checksum mismatch. Restore was not started.'
    }
}
$composeArguments = @('compose', '--project-directory', $workspace, '--project-name', $ProjectName, '--file', $composePath)
if ($EnvFile) {
    $composeArguments += @('--env-file', (Resolve-Path -LiteralPath $EnvFile -ErrorAction Stop).ProviderPath)
}

# Refuse an active application so it cannot publish or migrate while tables are restored.
$checkInfo = [Diagnostics.ProcessStartInfo]::new()
$checkInfo.FileName = $dockerCommand
$checkInfo.WorkingDirectory = $workspace
$checkInfo.UseShellExecute = $false
$checkInfo.CreateNoWindow = $true
$checkInfo.RedirectStandardOutput = $true
$checkInfo.RedirectStandardError = $true
foreach ($argument in @('ps', '--filter', ('label=com.docker.compose.project=' + $ProjectName),
        '--filter', 'label=com.docker.compose.service=app', '--quiet')) {
    $checkInfo.ArgumentList.Add($argument)
}
$check = [Diagnostics.Process]::new()
$check.StartInfo = $checkInfo
try {
    if (-not $check.Start()) { throw 'Could not inspect the app service.' }
    $outputTask = $check.StandardOutput.ReadToEndAsync()
    $errorTask = $check.StandardError.ReadToEndAsync()
    $check.WaitForExit()
    $runningApp = $outputTask.GetAwaiter().GetResult()
    $null = $errorTask.GetAwaiter().GetResult()
    if ($check.ExitCode -ne 0) { throw 'Could not inspect the compose project; restore was not started.' }
    if (-not [string]::IsNullOrWhiteSpace($runningApp)) { throw 'Stop this compose project app service before restoring. Restore was not started.' }
} finally { $check.Dispose() }

$info = [Diagnostics.ProcessStartInfo]::new()
$info.FileName = $dockerCommand
$info.WorkingDirectory = $workspace
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardInput = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
foreach ($argument in $composeArguments) { $info.ArgumentList.Add($argument) }
# Credentials remain inside the db container; schema/user are provisioned only for this project.
$restoreCommand = 'export MYSQL_PWD="$MYSQL_PASSWORD"; exec mysql --user="$MYSQL_USER" --database="$MYSQL_DATABASE" --default-character-set=utf8mb4 --binary-mode=1'
foreach ($argument in @('exec', '-T', 'db', 'sh', '-c', $restoreCommand)) { $info.ArgumentList.Add($argument) }
$process = [Diagnostics.Process]::new()
$process.StartInfo = $info
$stream = $null
try {
    $stream = [IO.File]::Open($backupPath, [IO.FileMode]::Open, [IO.FileAccess]::Read, [IO.FileShare]::Read)
    if (-not $process.Start()) { throw 'Could not start the restore command.' }
    $outputTask = $process.StandardOutput.ReadToEndAsync()
    $errorTask = $process.StandardError.ReadToEndAsync()
    $stream.CopyTo($process.StandardInput.BaseStream)
    $pauseSql = "`nUPDATE publication_control SET paused=TRUE WHERE id=1;`nINSERT INTO publication_control_audit(id,paused,changed_at) VALUES(UUID(),TRUE,UTC_TIMESTAMP(6));`n"
    $pauseBytes = [Text.Encoding]::UTF8.GetBytes($pauseSql)
    $process.StandardInput.BaseStream.Write($pauseBytes, 0, $pauseBytes.Length)
    $process.StandardInput.Close()
    $process.WaitForExit()
    $null = $outputTask.GetAwaiter().GetResult()
    $null = $errorTask.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) {
        throw ('MySQL restore failed with exit code ' + $process.ExitCode + '. Keep the app stopped; the schema may be partially restored.')
    }
    Write-Output 'Restore completed. Publication is paused and the app remains stopped. Reconcile Telegram deliveries after the backup before resuming.'
} finally {
    if ($stream) { $stream.Dispose() }
    try { if (-not $process.HasExited) { $process.Kill($true); $process.WaitForExit() } } catch { }
    $process.Dispose()
}
