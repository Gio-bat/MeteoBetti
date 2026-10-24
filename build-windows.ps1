$ErrorActionPreference = "Stop"

$ProjectRoot = $PSScriptRoot
$BuildRoot = Join-Path $ProjectRoot "build\windows"
$ClassesDir = Join-Path $BuildRoot "classes"
$InputDir = Join-Path $BuildRoot "input"
$DistDir = Join-Path $BuildRoot "dist"
$AppName = "MeteoBettiUltimateOS"
$AppVersion = if ($env:APP_VERSION) { $env:APP_VERSION } else { "1.0.0" }
$AppImage = Join-Path $DistDir $AppName
$ArchivePath = Join-Path $DistDir "$AppName-windows.zip"

foreach ($tool in @("javac", "jar", "jpackage")) {
    if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
        throw "Comando richiesto non trovato: $tool. Installa un JDK 25 e aggiungilo al PATH."
    }
}

if (Test-Path $ClassesDir) {
    Remove-Item -LiteralPath $ClassesDir -Recurse -Force
}
if (Test-Path $InputDir) {
    Remove-Item -LiteralPath $InputDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $ClassesDir, $InputDir, $DistDir | Out-Null

& javac -encoding UTF-8 -d $ClassesDir (Join-Path $ProjectRoot "MeteoBettiUltimateOS.java")
if ($LASTEXITCODE -ne 0) { throw "Compilazione Java non riuscita." }

$JarPath = Join-Path $InputDir "$AppName.jar"
& jar --create --file $JarPath `
    -C $ClassesDir . `
    -C $ProjectRoot sfondo_sole.jpg `
    -C $ProjectRoot sfondo_pioggia.jpg `
    -C $ProjectRoot sfondo_nuvoloso.jpg
if ($LASTEXITCODE -ne 0) { throw "Creazione del JAR non riuscita." }

if (Test-Path $AppImage) {
    Remove-Item -LiteralPath $AppImage -Recurse -Force
}

& jpackage `
    --type app-image `
    --name $AppName `
    --input $InputDir `
    --main-jar "$AppName.jar" `
    --main-class MeteoBettiUltimateOS `
    --dest $DistDir `
    --app-version $AppVersion `
    --vendor "MeteoBetti"
if ($LASTEXITCODE -ne 0) { throw "Creazione dell'app Windows non riuscita." }

if (Test-Path $ArchivePath) {
    Remove-Item -LiteralPath $ArchivePath -Force
}
Compress-Archive -Path $AppImage -DestinationPath $ArchivePath -Force
Write-Output "Pacchetto creato: $ArchivePath"
