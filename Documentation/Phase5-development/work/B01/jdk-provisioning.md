# B01 Temurin provisioning evidence

**Result on 2026-11-22 (Europe/Warsaw).** The coordinator downloaded the authorized portable Windows x64 Temurin HotSpot `25.0.5.1+2` ZIP and verified the publisher’s SHA-256 `849d380ab0a64d9098e7d3a2de28beb73841c3b255c8284f019ccd8748c5eb5b` before extraction. The restricted shell failed DNS for `github.com`. The sandbox approval for the same publisher download and local extraction succeeded. [G0-B approval](g0-b-approval.md) · [Publisher checksum](https://github.com/adoptium/temurin25-binaries/releases/expanded_assets/jdk-25.0.5.1%2B2)

The verified archive and portable extraction are under `tmp/phase5-development/b01-tools/`. `java.exe -version` reported Eclipse Adoptium Temurin `25.0.5.1+2-LTS` and `javac.exe -version` reported `25.0.5.1`, both with exit 0. Maven `3.9.16`, build `2bdd9fddda4b155ebf8000e807eb73fd829a51d5`, reported Eclipse Adoptium `25.0.5.1` with a process-local `JAVA_HOME` and exited 0. The same localized access-denied diagnostic seen in the earlier launch probe remains. [Maven preflight](maven-qualification.md#executable-and-offline-preflight)

```powershell
$toolDir = Join-Path (Get-Location).Path 'tmp/phase5-development/b01-tools'
$archive = Join-Path $toolDir 'OpenJDK25U-jdk_x64_windows_hotspot_25.0.5.1_2.zip'
$jdk = Join-Path $toolDir 'temurin/jdk-25.0.5.1+2'
Get-FileHash -Algorithm SHA256 -LiteralPath $archive
& (Join-Path $jdk 'bin/java.exe') -version
& (Join-Path $jdk 'bin/javac.exe') -version
$env:JAVA_HOME = $jdk
$env:MAVEN_SKIP_RC = 'true'
& 'C:/Program Files/Apache/apache-maven-3.9.16/bin/mvn.cmd' --version
```
