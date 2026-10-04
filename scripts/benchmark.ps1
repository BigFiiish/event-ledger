param([int]$Forks = 3)
$ErrorActionPreference = 'Stop'
if ($Forks -lt 3) { throw 'Use at least three independent JVM forks.' }
Push-Location (Split-Path $PSScriptRoot -Parent)
try {
    $run = Join-Path 'reports/raw' (Get-Date -Format 'yyyyMMdd-HHmmss')
    New-Item -ItemType Directory -Path $run -Force | Out-Null
    $sourceHashes = Get-ChildItem src -Recurse -File | Sort-Object FullName | ForEach-Object {
        @{ path=$_.FullName.Substring((Get-Location).Path.Length+1); sha256=(Get-FileHash $_.FullName).Hash }
    }
    $cpu = Get-CimInstance Win32_Processor | Select-Object Name,NumberOfCores,NumberOfLogicalProcessors
    @{recordedAt=(Get-Date).ToUniversalTime().ToString('o');cpu=$cpu;os=[Environment]::OSVersion.VersionString;
      java=(& java -version 2>&1 | Out-String);flags='-Xms512m -Xmx512m -XX:+UseG1GC';
      workload='synthetic seed=42; 4096 dense IDs; 8192 ticks; 300000 service / 60000 queued events';source=$sourceHashes} |
        ConvertTo-Json -Depth 6 | Set-Content (Join-Path $run 'environment.json') -Encoding utf8
    for($fork=1;$fork -le $Forks;$fork++) {
        & java -Xms512m -Xmx512m -XX:+UseG1GC -cp target/classes io.github.bigfiiish.eventledger.Benchmark (Join-Path $run "fork-$fork.csv")
        if($LASTEXITCODE -ne 0) { throw "Benchmark fork $fork failed; partial results retained." }
    }
    Write-Output "RESULT_DIRECTORY=$run"
} finally { Pop-Location }
