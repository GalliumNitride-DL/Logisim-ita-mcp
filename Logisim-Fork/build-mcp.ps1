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
#   ./build-mcp.ps1 -Console   make the exe launchers console ones. Needed only for
#                              MCP over stdio through an exe: a plain Windows launcher
#                              has no stdio, so for stdio use the fat jar, run the exe
#                              with -mcp --transport tcp --port 7331, or build with
#                              -Console. HTTP (and --with-gui) needs no console: the
#                              server notices there is no stdin and serves until it is
#                              stopped, so the plain exe is a working MCP server.
#   ./build-mcp.ps1 -Single    one exe file with the fat jar wrapped inside it
#                              (build/dist/Logisim-ITA.exe, launch4j), the same shape
#                              as the released Compiled/Logisim-ITA.exe. It needs a
#                              Java 8 or newer runtime on the machine, unlike -Exe,
#                              which carries its own. -Console applies here too.
#                              -Exe and -Single build the fat jar themselves (-Jar is
#                              implied): they wrap it, so wrapping a jar left behind by
#                              an earlier run would package older code.
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
    [switch]$Single,
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
        ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' } |
        Set-Content $list -Encoding ASCII
    $count = (Get-Content $list).Count

    Write-Host "compiling $count sources with $javac"
    # javac writes its "Note: ..." lines to stderr. With the strict preference above a
    # native stderr line becomes a terminating error, and even when it does not, a caller
    # piping this script's output gets a red NativeCommandError block for what is only a
    # note. So relax the preference around the call, re-emit the lines as ordinary text,
    # and judge success by the exit code - real compiler errors still come through.
    $strict = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    $output = & $javac -source 8 -target 8 -encoding UTF-8 -nowarn -d $out -cp "libs/*" "@$list" 2>&1
    $code = $LASTEXITCODE
    $ErrorActionPreference = $strict
    foreach ($line in $output) { Write-Output "$line" }
    if ($code -ne 0) { throw "compilation failed: javac exited with $code" }
    Write-Host "ok: $out ($count sources)"

    # -Exe and -Single both wrap the fat jar, so neither can be built without it: asking
    # for either one builds the jar as well. That implication is what $pack is for -
    # -Single on its own used to compile the sources and then wrap whatever jar an
    # earlier run had left in build/dist, i.e. package older code, which is how an exe
    # came to offer 54 tools while the tree it was built beside had 56.
    $pack = [bool]$Jar -or [bool]$Exe -or [bool]$Single
    if ($pack -and -not $Jar) {
        Write-Host "note: -Jar is implied by -Exe/-Single, which wrap the fat jar"
    }
    if (-not $pack) {
        # A jar an earlier run left behind is older code, and running it - or wrapping it
        # - reports the tool set of that earlier build. Say so, because a jar that is
        # quietly one build behind looks exactly like a broken build.
        $newest = (Get-ChildItem -Recurse -Filter *.class $out |
            Sort-Object LastWriteTime -Descending | Select-Object -First 1).LastWriteTime
        foreach ($old in @("build\dist\app\Logisim-jar-with-dependencies.jar", "build\dist\Logisim.jar")) {
            if ((Test-Path $old) -and (Get-Item $old).LastWriteTime -lt $newest) {
                Write-Host "note: $old is older than the classes just compiled - run with -Jar to rebuild it, or it carries older code"
            }
        }
    }

    # ------------------------------------------------------------------ packaging
    if ($pack) {
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
        & $jartool cf $thin -C $out . -C resources . -C doc .
        if ($LASTEXITCODE -ne 0) { throw "jar failed for $thin" }
        Write-Host ("ok: {0} ({1:N1} MB)" -f $thin, ((Get-Item $thin).Length / 1MB))

        # The fat jar: the same, plus every dependency unpacked into it, with our own
        # manifest last so that `java -jar` finds the main class.
        $stage = "build\package"
        Remove-Item -Recurse -Force $stage -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path (Join-Path $stage "resources") | Out-Null
        Copy-Item -Recurse -Force "$out\*" $stage
        Copy-Item -Recurse -Force "resources\*" (Join-Path $stage "resources")
        # The manual, all languages (about 4.5 MB). Both the GUI's Help menu and the MCP
        # logisim_help tool read doc/... through the classpath, so a jar without it has
        # no documentation at all - which is exactly what the packaged exe used to be.
        Copy-Item -Recurse -Force "doc" (Join-Path $stage "doc")
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
        if (-not (Test-Path $appDir)) { throw "the fat jar is missing: -Exe needs -Jar, which it normally implies" }
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

    if ($Single) {
        # launch4j: a single exe file with the fat jar wrapped inside it, which is what
        # the released Compiled/Logisim-ITA.exe is. The tooling is not part of this tree
        # (it comes with the Maven build of the 2.16.2.2 branch), so it is staged out of
        # the local Maven cache; nothing is downloaded.
        $dist = "build\dist"
        $fat = Join-Path $dist "app\Logisim-jar-with-dependencies.jar"
        if (-not (Test-Path $fat)) { throw "the fat jar is missing: -Single needs -Jar, which it normally implies" }
        $ErrorActionPreference = "Continue"

        $m2 = Join-Path $env:USERPROFILE ".m2\repository"
        $l4j = Join-Path $m2 "net\sf\launch4j"
        $core = Get-ChildItem $l4j -Recurse -Filter "*core.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
        $work = Get-ChildItem $l4j -Recurse -Directory -Filter "*workdir-win32" -ErrorAction SilentlyContinue | Select-Object -First 1
        $xstream = Get-ChildItem (Join-Path $m2 "com\thoughtworks\xstream") -Recurse -Filter "xstream-*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
        if (-not $core -or -not $work -or -not $xstream) {
            throw "-Single needs launch4j and xstream in the Maven cache (net.sf.launch4j:launch4j, com.thoughtworks.xstream:xstream)"
        }

        # launch4j looks for head/, bin/ and w32api/ beside its own jar, so stage it.
        $tools = "build\tools\launch4j"
        Remove-Item -Recurse -Force $tools -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $tools | Out-Null
        Copy-Item $core.FullName $tools -Force
        Copy-Item $xstream.FullName $tools -Force
        Copy-Item -Recurse -Force (Join-Path $work.FullName "*") $tools

        $header = if ($Console) { "console" } else { "gui" }
        $icon = "resources\logisim\img\logisim-icon.ico"
        $iconXml = if (Test-Path $icon) { "  <icon>$(Resolve-Path $icon)</icon>" } else { "" }
        $config = "build\launch4j.xml"
        @"
<?xml version="1.0" encoding="UTF-8"?>
<launch4jConfig>
  <dontWrapJar>false</dontWrapJar>
  <headerType>$header</headerType>
  <jar>$((Resolve-Path $fat).Path)</jar>
  <outfile>$((Resolve-Path $dist).Path)\Logisim-ITA.exe</outfile>
  <chdir>.</chdir>
  <priority>normal</priority>
  <stayAlive>false</stayAlive>
  <restartOnCrash>false</restartOnCrash>
$iconXml
  <jre>
    <minVersion>1.8.0</minVersion>
    <jdkPreference>preferJre</jdkPreference>
    <runtimeBits>64/32</runtimeBits>
  </jre>
</launch4jConfig>
"@ | Set-Content -Path $config -Encoding UTF8

        & java "-cp" "$tools\*" net.sf.launch4j.Main $config | Select-String -Pattern 'Successfully created'
        if ($LASTEXITCODE -ne 0) { throw "launch4j failed" }
        # NB: not $exe - PowerShell variable names are case-insensitive, so that would be
        # the [switch]$Exe parameter and assigning a path to it fails.
        $exePath = Join-Path $dist "Logisim-ITA.exe"
        Write-Host ("ok: {0} ({1:N1} MB, jar wrapped inside)" -f $exePath, ((Get-Item $exePath).Length / 1MB))
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
