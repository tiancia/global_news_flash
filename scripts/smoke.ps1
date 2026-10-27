#requires -Version 7.0
param(
    [switch]$SkipBuild,
    [string]$OutputDirectory = (Join-Path ([IO.Path]::GetTempPath()) 'global-news-flash/smoke')
)
$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
Push-Location $workspace
$serverProcess = $null
$previousToken = [Environment]::GetEnvironmentVariable('NEWS_OPS_TOKEN', 'Process')
try {
    if (-not $SkipBuild) {
        & .\mvnw.cmd -B -ntp verify
        if ($LASTEXITCODE -ne 0) { throw 'Build failed' }
    }
    $jarPath = Join-Path $workspace 'target\global-news-flash-0.1.0-SNAPSHOT.jar'
    if (-not (Test-Path -LiteralPath $jarPath)) { throw 'Build the JAR first' }
    $runDirectory = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) ([guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null
    $databasePath = (Join-Path $runDirectory 'news').Replace('\', '/')
    $databaseOption = '--spring.datasource.url=jdbc:h2:file:' + $databasePath + ';MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE'
    # Oracle's PATH shim can detach a child JVM; launch the actual runtime so cleanup owns its PID.
    $javaLauncher = (Get-Command java).Source
    $runtimeSettings = & $javaLauncher -XshowSettings:properties -version 2>&1
    if ($LASTEXITCODE -ne 0) { throw 'Could not locate the Java runtime' }
    $javaHomeLine = @($runtimeSettings | ForEach-Object { [string]$_ } | Where-Object { $_ -match '^\s*java.home\s*=' })
    if ($javaHomeLine.Count -ne 1) { throw 'Expected one Java runtime directory' }
    $javaRuntimeDirectory = ($javaHomeLine[0] -replace '^\s*java.home\s*=\s*', '').Trim()
    $javaCommand = Join-Path $javaRuntimeDirectory 'bin/java.exe'
    if (-not (Test-Path -LiteralPath $javaCommand -PathType Leaf)) { throw 'Java runtime executable unavailable' }
    # Isolate verification from the developer's live environment.
    $offlineOptions = @('--news.runtime.mode=OFFLINE', '--news.runtime.external-enabled=false',
        '--news.runtime.preferred-provider=openai',
        '--news.automation.enabled=false', '--news.ingestion.enabled=false', '--news.telegram.enabled=false',
        '--news.alert.enabled=false', '--news.digest.enabled=false', '--news.publication.worker-enabled=false')
    $results = @()
    for ($iteration = 1; $iteration -le 2; $iteration++) {
        $cliOutput = & $javaCommand '-Dfile.encoding=UTF-8' -jar $jarPath '--spring.profiles.active=offline,demo' --spring.main.web-application-type=none --news.demo.enabled=true $databaseOption @offlineOptions 2>&1
        $exitCode = $LASTEXITCODE
        $cliOutput | Out-File -LiteralPath (Join-Path $runDirectory ('cli-' + $iteration + '.log')) -Encoding utf8
        if ($exitCode -ne 0) { throw ('CLI process failed; inspect ' + $runDirectory) }
        $resultLine = @($cliOutput | ForEach-Object { [string]$_ } | Where-Object { $_.StartsWith('SYNTHETIC_DEMO_RESULT=') })
        if ($resultLine.Count -ne 1) { throw 'Expected exactly one demo result' }
        $results += ($resultLine[0].Substring('SYNTHETIC_DEMO_RESULT='.Length) | ConvertFrom-Json)
    }
    if ($results[0].pipeline.duplicate -or -not $results[1].pipeline.duplicate) { throw 'Restart deduplication failed' }
    if ($results[0].messageId -ne $results[1].messageId -or $results[1].deliveryState -ne 'SENT') { throw 'Restart message identity failed' }
    $env:NEWS_OPS_TOKEN = [Convert]::ToHexString([Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    $listener = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
    $listener.Start(); $localPort = $listener.LocalEndpoint.Port; $listener.Stop()
    $serverArgs = @('-Dfile.encoding=UTF-8', '-jar', ('"' + $jarPath + '"'), '--spring.profiles.active=offline,demo',
        '--news.demo.enabled=false', ('--server.port=' + $localPort), ('"' + $databaseOption + '"')) + $offlineOptions
    $serverProcess = Start-Process -FilePath $javaCommand -ArgumentList $serverArgs -WorkingDirectory $workspace -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $runDirectory 'server.log') -RedirectStandardError (Join-Path $runDirectory 'server-error.log')
    $base = 'http://127.0.0.1:' + $localPort
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    $healthy = $false
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($serverProcess.HasExited) { throw ('Local server exited; inspect ' + $runDirectory) }
        try {
            $health = Invoke-RestMethod ($base + '/actuator/health') -TimeoutSec 2
            if ($health.status -eq 'UP') { $healthy = $true; break }
        } catch { Start-Sleep -Milliseconds 250 }
    }
    if (-not $healthy) { throw 'Health check timed out' }
    $unauthorized = $false
    try { Invoke-RestMethod ($base + '/ops/status') -TimeoutSec 5 | Out-Null }
    catch { $unauthorized = [int]$_.Exception.Response.StatusCode -eq 401 }
    if (-not $unauthorized) { throw 'Unauthenticated operations access was not rejected' }
    $headers = @{'X-Ops-Token' = $env:NEWS_OPS_TOKEN}
    $status = Invoke-RestMethod ($base + '/ops/status') -Headers $headers -TimeoutSec 5
    if ($status.mode -ne 'OFFLINE' -or $status.externalEnabled -or -not $status.paused) { throw 'Unsafe runtime status' }
    $scenarios = @{}
    foreach ($scenario in @('STALE', 'DISCOVERY', 'TITLE_ONLY', 'REVISED')) {
        $body = @{scenario=$scenario} | ConvertTo-Json -Compress
        $response = Invoke-RestMethod ($base + '/ops/demo') -Headers $headers -Method Post -ContentType application/json -Body $body -TimeoutSec 10
        if ($response.pipeline.publicationId) { throw ('A rejected scenario created a task: ' + $scenario) }
        $scenarios[$scenario] = $response.pipeline.reasons
    }
    $report = Invoke-RestMethod ($base + '/ops/report') -Headers $headers -TimeoutSec 5
    $operations = @(Invoke-RestMethod ($base + '/ops/publications') -Headers $headers -TimeoutSec 5)
    $attempts = @(Invoke-RestMethod ($base + '/ops/publications/' + $results[0].pipeline.publicationId + '/attempts') -Headers $headers -TimeoutSec 5)
    if ($operations.Count -ne 1 -or $attempts.Count -ne 1) { throw 'Repeated CLI run created duplicate delivery' }
    if ($report.growth.knownJoins -ne 1 -or $report.growth.unknownJoins -ne 1 -or $report.growth.distributions -ne 1) { throw 'Synthetic attribution totals differ' }
    $summary = @{health='UP';unauthenticatedOps=401;restartDuplicate=$results[1].pipeline.duplicate;
        sameMessageId=($results[0].messageId -eq $results[1].messageId);operations=$operations.Count;attempts=$attempts.Count;
        scenarios=$scenarios;growth=$report.growth;mode='SYNTHETIC_OFFLINE';artifacts=$runDirectory}
    $summary | ConvertTo-Json -Depth 8 | Tee-Object -FilePath (Join-Path $runDirectory 'result.json')
} finally {
    if ($serverProcess -and -not $serverProcess.HasExited) { Stop-Process -Id $serverProcess.Id; $serverProcess.WaitForExit() }
    [Environment]::SetEnvironmentVariable('NEWS_OPS_TOKEN', $previousToken, 'Process')
    Pop-Location
}
