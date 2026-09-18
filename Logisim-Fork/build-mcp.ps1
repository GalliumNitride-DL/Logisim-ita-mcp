# Builds the Logisim 2.15.0.1 tree together with the MCP layer, and optionally runs
# the MCP server. This commit predates Maven, so the build is a direct javac call.
#
#   ./build-mcp.ps1            compile into build/classes
#   ./build-mcp.ps1 -Clean     delete build/classes first
#   ./build-mcp.ps1 -Run       compile, then serve MCP over stdio (extra arguments
#                              are passed to Logisim, e.g. -Run --transport tcp)
#
# A JDK is required: -JavaHome <jdk>, else JAVA_HOME, else javac.exe on PATH.
# Do NOT add --release 8: part of this tree calls JDK 10+ APIs (guarded at run time
# by Main.JAVA_VERSION), so it has to compile against a modern API.
param(
    [switch]$Clean,
    [switch]$Run,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = "Stop"
Push-Location $PSScriptRoot
try {
    if (-not $JavaHome) {
        $found = Get-Command javac.exe -ErrorAction SilentlyContinue
        if ($found) { $JavaHome = Split-Path (Split-Path $found.Source -Parent) -Parent }
    }
    $javac = Join-Path $JavaHome "bin\javac.exe"
    if (-not (Test-Path $javac)) {
        throw "javac not found: pass -JavaHome <jdk path> or set JAVA_HOME"
    }

    $out = "build\classes"
    if ($Clean) { Remove-Item -Recurse -Force "build" -ErrorAction SilentlyContinue }
    New-Item -ItemType Directory -Force -Path $out | Out-Null

    $list = "build\sources.txt"
    Get-ChildItem -Recurse -Filter *.java com |
        ForEach-Object { $_.FullName.Replace('\', '/') } |
        Set-Content $list -Encoding ASCII
    $count = (Get-Content $list).Count

    Write-Host "compiling $count sources with $javac"
    & $javac -source 8 -target 8 -encoding UTF-8 -nowarn -d $out -cp "libs/*" "@$list"
    if ($LASTEXITCODE -ne 0) { throw "compilation failed" }
    Write-Host "ok: $out"

    if ($Run) {
        # The project root has to be on the classpath, not resources/: the locale
        # bundles are looked up as resources/logisim/..., and without it every open
        # answers "No locale bundles are available".
        & java "-Xmx512m" -cp "$out;.;libs/*" com.cburch.logisim.Main -mcp $args
    }
} finally {
    Pop-Location
}
