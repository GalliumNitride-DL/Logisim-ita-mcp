# Builds the Logisim 2.15.0.1 tree together with the MCP layer, and optionally runs
# the MCP server. This commit predates Maven, so the build is a direct javac call.
#
#   ./build-mcp.ps1            compile into build/classes
#   ./build-mcp.ps1 -Clean     delete build/classes first
#   ./build-mcp.ps1 -Run       compile, then serve MCP over stdio (extra arguments
#                              are passed to Logisim, e.g. -Run --transport tcp)
#   ./build-mcp.ps1 -Jar       also package build/dist/Logisim.jar (classes plus the
#                              resources tree) and build/dist/app/Logisim-jar-with-
#                              dependencies.jar (dependencies unpacked, so it runs
#                              with `java -jar` and needs nothing else)
#   ./build-mcp.ps1 -Exe       also build the fat jar and wrap it as a Windows
#                              application image: build/dist/Logisim/Logisim.exe
#                              (jpackage, JDK 14+, bundles its own runtime)
#   ./build-mcp.ps1 -Console   make that exe a console launcher. Needed only for MCP
#                              over stdio through the exe: a plain Windows launcher
#                              has no stdio, so there use the fat jar instead, or run
#                              the exe with -mcp --transport tcp --port 7331
#   switches combine, e.g. ./build-mcp.ps1 -Clean -Jar -Exe
#
# A JDK is required: -JavaHome <jdk>, else JAVA_HOME, else javac.exe on PATH.
# It must be JDK 14 or newer for -Exe, and 9 or newer for -Jar.
# Do NOT add --release 8: part of this tree calls JDK 10+ APIs (guarded at run time
# by Main.JAVA_VERSION), so it has to compile against a modern API.
#
# To start the GUI instead of the MCP server:
#   java -cp "build/classes;.;libs/*" com.cburch.logisim.Main
# From the fat jar, either way round:
#   java -jar build/dist/app/Logisim-jar-with-dependencies.jar -mcp
param(
    [switch]$Clean,
    [switch]$Run,
    [switch]$Jar,
    [switch]$Exe,
    [switch]$Console,
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
    $jartool = Join-Path $JavaHome "bin\jar.exe"
    $jpackage = Join-Path $JavaHome "bin\jpackage.exe"

    $out = "build\classes"
    if ($Clean) { Remove-Item -Recurse -Force "build" -ErrorAction SilentlyContinue }
    New-Item -ItemType Directory -Force -Path $out | Out-Null

    $list = "build\sources.txt"
    Get-ChildItem -Recurse -Filter *.java com |
        ForEach-Object { $_.FullName.Replace('\', '/') } |
        Set-Content $list -Encoding ASCII
    $count = (Get-Content $list).Count

    Write-Host "compiling $count sources with $javac"
    # javac writes its "Note: ..." lines to stderr. With the strict preference above, and
    # also when a caller pipes this script's output, PowerShell turns that into a
    # terminating error, so relax it around the native calls and judge success by the
    # exit code instead.
    $strict = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    & $javac -source 8 -target 8 -encoding UTF-8 -nowarn -d $out -cp "libs/*" "@$list"
    $code = $LASTEXITCODE
    $ErrorActionPreference = $strict
    if ($code -ne 0) { throw "compilation failed: javac exited with $code" }
    Write-Host "ok: $out ($count sources)"

    # ------------------------------------------------------------------ packaging
    if ($Jar -or $Exe) {
        if (-not (Test-Path $jartool)) { throw "-Jar needs the jar tool of the same JDK as javac" }
        $ErrorActionPreference = "Continue"

        $dist = "build\dist"
        $appDir = Join-Path $dist "app"
        Remove-Item -Recurse -Force $dist -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $appDir | Out-Null

        # A library jar: the compiled classes plus the resource tree, so that
        # resources/logisim/... resolves. Use it with the jars from libs/ on the
        # classpath; the fat jar below is the one that runs on its own.
        $thin = Join-Path $dist "Logisim.jar"
        & $jartool cf $thin -C $out . -C resources .
        if ($LASTEXITCODE -ne 0) { throw "jar failed for $thin" }
        Write-Host ("ok: {0} ({1:N1} MB)" -f $thin, ((Get-Item $thin).Length / 1MB))

        # The fat jar: the same, plus every dependency unpacked into it, with our own
        # manifest last so that `java -jar` finds the main class.
        $stage = "build\package"
        Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path (Join-Path $stage "resources") | Out-Null
        Copy-Item -Recurse -Force "$out\*" $stage
        Copy-Item -Recurse -Force "resources\*" (Join-Path $stage "resources")
        foreach ($lib in Get-ChildItem libs\*.jar) {
            Push-Location $stage
            & $jartool xf $lib.FullName
            Pop-Location
        }
        # dependencies bring their own manifests and signature files, which must not
        # shadow the one we are about to write
        Remove-Item -Force (Join-Path $stage "META-INF\MANIFEST.MF") -ErrorAction SilentlyContinue
        foreach ($pattern in @("*.SF", "*.RSA", "*.DSA")) {
            Remove-Item -Force (Join-Path $stage "META-INF\$pattern") -ErrorAction SilentlyContinue
        }
        $manifest = "build\manifest.txt"
        Set-Content -Path $manifest -Encoding ASCII -Value "Manifest-Version: 1.0`r`nMain-Class: com.cburch.logisim.Main`r`n"
        $fat = Join-Path $appDir "Logisim-jar-with-dependencies.jar"
        & $jartool cfm $fat $manifest -C $stage .
        if ($LASTEXITCODE -ne 0) { throw "jar failed for $fat" }
        Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
        Write-Host ("ok: {0} ({1:N1} MB)" -f $fat, ((Get-Item $fat).Length / 1MB))
    }

    if ($Exe) {
        if (-not (Test-Path $jpackage)) { throw "-Exe needs jpackage: use a JDK 14 or newer" }
        $ErrorActionPreference = "Continue"
        $dist = "build\dist"
        $appDir = Join-Path $dist "app"
        if (-not (Test-Path $appDir)) { throw "-Exe needs the fat jar: run with -Jar as well" }
        $launcher = @()
        if ($Console) { $launcher = @("--win-console") }
        & $jpackage --type app-image --name Logisim --dest $dist --input $appDir `
            --main-jar "Logisim-jar-with-dependencies.jar" `
            --main-class com.cburch.logisim.Main `
            --java-options "-Xmx1g" `
            --app-version "2.15.0.1" `
            --description "Logisim 2.15.0.1 with the MCP server (-mcp)" @launcher
        if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }
        Write-Host "ok: $(Join-Path $dist 'Logisim\Logisim.exe')"
    }

    if ($Run) {
        # The project root has to be on the classpath, not resources/: the locale
        # bundles are looked up as resources/logisim/..., and without it every open
        # answers "No locale bundles are available".
        $ErrorActionPreference = "Continue"
        & java "-Xmx512m" -cp "$out;.;libs/*" com.cburch.logisim.Main -mcp $args
    }
} finally {
    Pop-Location
}
