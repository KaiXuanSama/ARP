<#
.SYNOPSIS
    ARP Windows packaging script
.DESCRIPTION
    Packages the Spring Boot jar + tray launcher + icon into a distributable directory and zip.
.NOTES
    Usage: .\launcher\package.ps1 [-SkipBuild] [-SkipJre] [-CompileLauncher]
#>

param(
    [switch]$SkipBuild,
    [switch]$SkipJre,
    [switch]$CompileLauncher
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path (Split-Path $MyInvocation.MyCommand.Path -Parent) -Parent
$launcherDir = Join-Path $projectRoot "launcher"
$distDir     = Join-Path $projectRoot "dist"
$outputDir   = Join-Path $distDir "ARP"

Write-Host "========================================"
Write-Host "  ARP Windows Packaging"
Write-Host "========================================"
Write-Host ""

# -- Step 1: Build jar --
if (-not $SkipBuild) {
    Write-Host "[1/5] Building Spring Boot jar..."
    Push-Location $projectRoot
    & .\mvnw.cmd clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    Pop-Location
} else {
    Write-Host "[1/5] Skipping jar build (-SkipBuild)"
}

# Find jar
$jarFile = Get-ChildItem (Join-Path $projectRoot "target") -Filter "agentreproxy*.jar" |
           Where-Object { $_.Name -notlike "*.original" } |
           Select-Object -First 1
if (-not $jarFile) { throw "Cannot find built jar file" }
Write-Host "  jar: $($jarFile.Name)"

# -- Step 2: Compile tray launcher --
$launcherExe = Join-Path $launcherDir "ARP.exe"
if ($CompileLauncher -or -not (Test-Path $launcherExe)) {
    Write-Host "[2/5] Compiling tray launcher..."
    $csc = "C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe"
    if (-not (Test-Path $csc)) { throw "Cannot find .NET Framework csc compiler" }

    $cscArgs = @("/target:winexe", "/out:ARP.exe",
                 "/reference:System.Windows.Forms.dll",
                 "/reference:System.Drawing.dll")

    $icoFile = Join-Path $launcherDir "arp.ico"
    if (Test-Path $icoFile) {
        $cscArgs += "/win32icon:$icoFile"
    }
    $cscArgs += "ArpLauncher.cs"

    Push-Location $launcherDir
    & $csc @cscArgs
    if ($LASTEXITCODE -ne 0) { throw "Launcher compilation failed" }
    Pop-Location
} else {
    Write-Host "[2/5] Launcher exe already exists, skipping"
}

# -- Step 3: Prepare output directory --
Write-Host "[3/5] Preparing output directory..."
if (Test-Path $outputDir) { Remove-Item $outputDir -Recurse -Force }
New-Item $outputDir -ItemType Directory -Force | Out-Null

Copy-Item $jarFile.FullName (Join-Path $outputDir "agentreproxy.jar")
Copy-Item $launcherExe (Join-Path $outputDir "ARP.exe")

$icoSrc = Join-Path $launcherDir "arp.ico"
if (Test-Path $icoSrc) {
    Copy-Item $icoSrc (Join-Path $outputDir "arp.ico")
}

$modelsConfig = Join-Path $projectRoot "modelsconfig.json"
if (Test-Path $modelsConfig) {
    Copy-Item $modelsConfig (Join-Path $outputDir "modelsConfig.json")
}

# Copy Spring Boot config files (external config — Spring Boot auto-reads
# application.yml from the same directory as the jar)
$appYml = Join-Path $projectRoot "src\main\resources\application.yml"
if (Test-Path $appYml) {
    Copy-Item $appYml (Join-Path $outputDir "application.yml")
}
$schemaSQL = Join-Path $projectRoot "src\main\resources\schema.sql"
if (Test-Path $schemaSQL) {
    Copy-Item $schemaSQL (Join-Path $outputDir "schema.sql")
}

# -- Step 4: Optional bundled JRE --
if (-not $SkipJre) {
    Write-Host "[4/5] Generating minimal JRE via jlink..."
    $jlinkExe = $null

    $javaHome = $env:JAVA_HOME
    if ($javaHome) {
        $candidate = Join-Path $javaHome "bin\jlink.exe"
        if (Test-Path $candidate) { $jlinkExe = $candidate }
    }
    if (-not $jlinkExe) {
        $javaPath = (Get-Command java -ErrorAction SilentlyContinue).Source
        if ($javaPath) {
            $binDir = Split-Path $javaPath -Parent
            $homeDir = Split-Path $binDir -Parent
            $candidate = Join-Path $homeDir "bin\jlink.exe"
            if (Test-Path $candidate) { $jlinkExe = $candidate }
        }
    }

    if ($jlinkExe) {
        $jreDir = Join-Path $outputDir "jre"
        # Spring Boot + WebFlux + JDBC + SQLite needs a broad module set
        $modules = @(
            "java.base",
            "java.compiler",
            "java.desktop",
            "java.instrument",
            "java.logging",
            "java.management",
            "java.naming",
            "java.net.http",
            "java.prefs",
            "java.rmi",
            "java.scripting",
            "java.security.jgss",
            "java.security.sasl",
            "java.sql",
            "java.sql.rowset",
            "java.transaction.xa",
            "java.xml",
            "java.xml.crypto",
            "jdk.crypto.ec",
            "jdk.crypto.cryptoki",
            "jdk.jdwp.agent",
            "jdk.management",
            "jdk.naming.dns",
            "jdk.net",
            "jdk.unsupported",
            "jdk.zipfs"
        ) -join ","
        & $jlinkExe --add-modules $modules --strip-debug --no-man-pages --no-header-files --compress zip-6 --output $jreDir
        if ($LASTEXITCODE -eq 0) {
            $jreSize = [math]::Round((Get-ChildItem $jreDir -Recurse | Measure-Object -Property Length -Sum).Sum / 1MB)
            Write-Host "  JRE bundled: $jreSize MB"
        } else {
            Write-Host "  jlink failed, skipping JRE bundle"
        }
    } else {
        Write-Host "  jlink not found, skipping JRE bundle"
    }
} else {
    Write-Host "[4/5] Skipping JRE bundle (-SkipJre)"
}

# -- Step 5: Create zip --
Write-Host "[5/5] Creating zip archive..."
$zipFile = Join-Path $distDir "ARP-windows.zip"
if (Test-Path $zipFile) { Remove-Item $zipFile -Force }
Compress-Archive -Path $outputDir -DestinationPath $zipFile -CompressionLevel Optimal
$zipSize = [math]::Round((Get-Item $zipFile).Length / 1MB, 1)

Write-Host ""
Write-Host "========================================"
Write-Host "  Packaging complete!"
Write-Host "========================================"
Write-Host ""
Write-Host "  Output dir: $outputDir"
Write-Host "  Zip file:   $zipFile ($zipSize MB)"
Write-Host ""
Write-Host "  How to use:"
Write-Host "    1. Unzip ARP-windows.zip"
Write-Host "    2. Double-click ARP.exe"
Write-Host "    3. ARP icon appears in system tray"
Write-Host "    4. Browser opens http://127.0.0.1:8351"
Write-Host ""
