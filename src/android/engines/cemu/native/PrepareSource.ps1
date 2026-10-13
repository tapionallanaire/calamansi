param(
    [Parameter(Mandatory=$true)][string]$Upstream,
    [Parameter(Mandatory=$true)][string]$Destination,
    [Parameter(Mandatory=$true)][string]$VcpkgBuildRoot
)
$ErrorActionPreference = 'Stop'
$ownedDestination = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../build/upstream'))
$Destination = [IO.Path]::GetFullPath($Destination)
if (-not [String]::Equals($Destination, $ownedDestination, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Cemu source destination must be this module''s owned build/upstream directory'
}
$ownedVcpkgBuildRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../../../../../build/cemu'))
$VcpkgBuildRoot = [IO.Path]::GetFullPath($VcpkgBuildRoot)
if (-not [String]::Equals($VcpkgBuildRoot, $ownedVcpkgBuildRoot, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Cemu vcpkg output must be this repository''s owned build/cemu directory'
}
$pin = 'd2e7ddf61386f17d2c05bdaf182b907c0ae90592'
$patch = Join-Path $PSScriptRoot 'calamansi.patch'
$expected = (Get-Content -Raw (Join-Path $PSScriptRoot 'calamansi.patch.sha256')).Trim().ToLowerInvariant()
$actual = (Get-FileHash -Algorithm SHA256 $patch).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw 'Cemu patch hash mismatch' }
function Invoke-Git {
    param([Parameter(Mandatory=$true)][string[]]$GitArgs)
    & git @GitArgs
    if ($LASTEXITCODE -ne 0) { throw "git failed: $GitArgs" }
}
function Prepare-HostMsys {
    if ($env:OS -ne 'Windows_NT') { return }
    # MSYS2 prunes old package archives. The official Git for Windows SDK keeps
    # the complete POSIX host files in immutable Git objects instead.
    $sdkPin = '28a6cb0c47a5aa7b63e9d2bca8a8548095350b78'
    $sdkRoot = Join-Path $VcpkgBuildRoot 'h/msys'
    $sdkMarker = Join-Path $sdkRoot '.calamansi-sdk-pin'
    $packages = @(
        'make', 'perl', 'bash', 'coreutils', 'file', 'gawk', 'grep', 'gzip',
        'diffutils', 'pkgconf', 'sed', 'autoconf-wrapper', 'autoconf2.71',
        'automake-wrapper', 'automake1.16', 'binutils', 'libtool', 'which',
        'msys2-runtime', 'libintl', 'libiconv', 'gcc-libs', 'libreadline',
        'ncurses', 'libpcre', 'libxcrypt', 'gmp', 'mpfr', 'libbz2',
        'liblzma', 'libzstd', 'zlib', 'findutils', 'tar', 'm4'
    )
    if (-not (Test-Path (Join-Path $sdkRoot '.git'))) {
        Invoke-Git -GitArgs @('-c', 'core.longpaths=true', 'clone', '--filter=blob:none', '--no-checkout', '--depth=1', 'https://github.com/git-for-windows/git-sdk-64.git', $sdkRoot)
        Invoke-Git -GitArgs @('-C', $sdkRoot, 'config', 'core.longpaths', 'true')
        Invoke-Git -GitArgs @('-C', $sdkRoot, 'config', 'core.autocrlf', 'false')
        Invoke-Git -GitArgs @('-C', $sdkRoot, 'fetch', '--filter=blob:none', '--depth=1', 'origin', $sdkPin)
        Invoke-Git -GitArgs @('-C', $sdkRoot, 'sparse-checkout', 'set', '--no-cone', '/var/lib/pacman/local/')
        Invoke-Git -GitArgs @('-C', $sdkRoot, 'checkout', '--detach', $sdkPin)
    }
    $sdkRevision = (& git -C $sdkRoot rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $sdkRevision -ne $sdkPin) { throw 'Cemu MSYS host SDK pin changed' }
    Invoke-Git -GitArgs @('-C', $sdkRoot, 'diff', '--exit-code', 'HEAD', '--', 'usr', 'var/lib/pacman/local')
    if (-not (Test-Path $sdkMarker)) {
        $trackedFiles = & git -C $sdkRoot ls-tree -r --name-only $sdkPin -- usr
        if ($LASTEXITCODE -ne 0) { throw 'Cannot inspect pinned Cemu MSYS host files' }
        $tracked = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        foreach ($path in $trackedFiles) { $tracked.Add($path) | Out-Null }
        $paths = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
        $database = Join-Path $sdkRoot 'var/lib/pacman/local'
        foreach ($package in $packages) {
            $pattern = '^' + [Regex]::Escape($package) + '-[^-]+-[^-]+$'
            $installed = @(Get-ChildItem -Directory $database | Where-Object { $_.Name -match $pattern })
            if ($installed.Count -ne 1) { throw "Pinned Cemu MSYS SDK package is missing or ambiguous: $package" }
            $manifest = Get-Content -Raw (Join-Path $installed[0].FullName 'files')
            $files = [Regex]::Match($manifest, '(?ms)^%FILES%\r?\n(.*?)(?:\r?\n\r?\n|\z)')
            if (-not $files.Success) { throw "Invalid Cemu MSYS SDK file manifest: $package" }
            foreach ($path in ($files.Groups[1].Value -split '\r?\n')) {
                # Package manifests also list generated Python bytecode; fetch
                # only immutable SDK objects, not install-time cache files.
                if ($tracked.Contains($path)) { $paths.Add($path) | Out-Null }
            }
        }
        $patterns = @('/var/lib/pacman/local/')
        $patterns += $paths | Sort-Object | ForEach-Object { '/' + [Regex]::Replace($_, '([\\*?\[\]!])', '\$1') }
        $patterns | & git -C $sdkRoot sparse-checkout set --no-cone --stdin
        if ($LASTEXITCODE -ne 0) { throw 'Pinned Cemu MSYS host file checkout failed' }
        Set-Content -NoNewline -Encoding ASCII $sdkMarker $sdkPin
    } elseif ((Get-Content -Raw $sdkMarker).Trim() -ne $sdkPin) {
        throw 'Cemu MSYS host SDK marker changed'
    }
    $programHashes = @{
        'make.exe' = '2d3b9fca575be8e93c10ab74348055fa127a65cf628d52e9218ac76065cc6d22'
        'bash.exe' = '19af3e33574ff81b86da0679e3e81e7051ab2ece082ee10c1887f7aaf9e06bb0'
        'msys-2.0.dll' = '70323b309e05cf98c7fbf629730f9fbd86ff63b48e334e9ee9a7cdfef03c6a09'
        'perl.exe' = 'ed45197b8c2681834c56b6a52cabe3711fb1b8ba44e94916e511b42387c22714'
    }
    foreach ($program in $programHashes.Keys) {
        if ((Get-FileHash -Algorithm SHA256 (Join-Path $sdkRoot "usr/bin/$program")).Hash.ToLowerInvariant() -ne $programHashes[$program]) {
            throw "Pinned Cemu MSYS host program hash mismatch: $program"
        }
    }
    # Match vcpkg's drive mapping, keeping /tmp in the owned host tree.
    New-Item -ItemType Directory -Force -Path (Join-Path $sdkRoot 'etc'), (Join-Path $sdkRoot 'tmp') | Out-Null
    [IO.File]::WriteAllText((Join-Path $sdkRoot 'etc/fstab'), "none / cygdrive binary,posix=0,noacl,user 0 0`n", [Text.UTF8Encoding]::new($false))
    $hook = @'
# The pinned SDK supplies the complete declared POSIX host-package closure.
function(vcpkg_acquire_msys out_msys_root)
    cmake_parse_arguments(PARSE_ARGV 1 arg
        "NO_DEFAULT_PACKAGES;Z_ALL_PACKAGES"
        "Z_DECLARE_EXTRA_PACKAGES_COMMAND"
        "PACKAGES;DIRECT_PACKAGES")
    if(arg_UNPARSED_ARGUMENTS OR arg_Z_ALL_PACKAGES OR arg_Z_DECLARE_EXTRA_PACKAGES_COMMAND OR arg_DIRECT_PACKAGES)
        message(FATAL_ERROR "Cemu's pinned MSYS SDK does not support undeclared host packages")
    endif()
    set(available "@PACKAGES@")
    set(requested ${arg_PACKAGES})
    if(NOT arg_NO_DEFAULT_PACKAGES)
        list(APPEND requested bash coreutils file gawk grep gzip diffutils make pkgconf sed)
    endif()
    foreach(package IN LISTS requested)
        if(NOT package IN_LIST available)
            message(FATAL_ERROR "Cemu's pinned MSYS SDK does not provide ${package}")
        endif()
    endforeach()
    foreach(program IN ITEMS make.exe perl.exe bash.exe msys-2.0.dll)
        if(NOT EXISTS "@SDK_ROOT@/usr/bin/${program}")
            message(FATAL_ERROR "Cemu's pinned MSYS SDK is incomplete: ${program}")
        endif()
    endforeach()
    message(STATUS "Using pinned Cemu MSYS host SDK at @SDK_ROOT@")
    set("${out_msys_root}" "@SDK_ROOT@" PARENT_SCOPE)
endfunction()
'@
    $hook = $hook.Replace('@PACKAGES@', ($packages -join ';')).Replace('@SDK_ROOT@', $sdkRoot.Replace('\', '/'))
    $tripletRoot = Join-Path $VcpkgBuildRoot 't'
    New-Item -ItemType Directory -Force -Path $tripletRoot | Out-Null
    [IO.File]::WriteAllText((Join-Path $tripletRoot 'msys-host.cmake'), $hook, [Text.UTF8Encoding]::new($false))
}
function Prepare-HostPkgconf {
    $hostRoot = Join-Path $VcpkgBuildRoot 'h/pkgconf-3.0.8'
    $hostProgram = Join-Path $hostRoot 'pkgconf.exe'
    if ($env:OS -eq 'Windows_NT') {
        # The pinned vcpkg MSYS2 host archive was removed. Use a pinned official
        # release instead; read the MSI without installing it or touching registry.
        $archive = Join-Path $VcpkgBuildRoot 'd/pkgconf-x64-3.0.8.msi'
        $archiveHash = 'a236479bbe403b202816745bef19afe2d6240606f28ab1f008ba0f33fbfae43e'
        $programHash = '8f7caa492f72b83ef7f6b59317866142c29827a172233ad5edbc670f29dfc194'
        if (-not (Test-Path $hostProgram)) {
            New-Item -ItemType Directory -Force -Path $hostRoot, (Split-Path $archive) | Out-Null
            if (-not (Test-Path $archive)) {
                & curl.exe --fail --location --output $archive 'https://github.com/pkgconf/pkgconf/releases/download/pkgconf-3.0.8/pkgconf-x64-3.0.8.msi'
                if ($LASTEXITCODE -ne 0) { throw 'Official Cemu host pkgconf download failed' }
            }
            if ((Get-FileHash -Algorithm SHA256 $archive).Hash.ToLowerInvariant() -ne $archiveHash) {
                throw 'Official Cemu host pkgconf archive hash mismatch'
            }
            $cabinet = Join-Path $hostRoot 'cab1.cab'
            $installer = $database = $view = $record = $null
            try {
                $installer = New-Object -ComObject WindowsInstaller.Installer
                $database = $installer.OpenDatabase($archive, 0) # Read-only, no MSI actions.
                $view = $database.OpenView('SELECT `Data` FROM `_Streams` WHERE `Name` = ''cab1.cab''')
                $view.Execute()
                $record = $view.Fetch()
                if ($null -eq $record) { throw 'Official pkgconf MSI cabinet is missing' }
                $bytes = $record.ReadStream(1, [int](Get-Item $archive).Length, 1)
                [IO.File]::WriteAllBytes($cabinet, [Text.Encoding]::GetEncoding('iso-8859-1').GetBytes($bytes))
                $view.Close()
            } finally {
                foreach ($object in @($record, $view, $database, $installer)) {
                    if ($null -ne $object) { [Runtime.InteropServices.Marshal]::FinalReleaseComObject($object) | Out-Null }
                }
            }
            & expand.exe '-F:PkgconfExeFile' $cabinet $hostRoot
            if ($LASTEXITCODE -ne 0) { throw 'Official Cemu host pkgconf cabinet extraction failed' }
            Move-Item -LiteralPath (Join-Path $hostRoot 'PkgconfExeFile') -Destination $hostProgram
            Remove-Item -LiteralPath $cabinet
        }
        if ((Get-FileHash -Algorithm SHA256 $hostProgram).Hash.ToLowerInvariant() -ne $programHash) {
            throw 'Official Cemu host pkgconf executable hash mismatch'
        }
    }
    # Reuse every native triplet setting; only Windows host-tool discovery changes.
    $tripletRoot = Join-Path $VcpkgBuildRoot 't'
    New-Item -ItemType Directory -Force -Path $tripletRoot | Out-Null
    $nativeTriplet = (Join-Path $PSScriptRoot 'triplets/arm64-android.cmake').Replace('\', '/')
    # vcpkg hashes the wrapper, not the included triplet: invalidate old-ABI binaries.
    $nativeTripletHash = (Get-FileHash -Algorithm SHA256 $nativeTriplet).Hash.ToLowerInvariant()
    $hostProgram = $hostProgram.Replace('\', '/')
    $hostHook = (Join-Path $tripletRoot 'msys-host.cmake').Replace('\', '/')
    $triplet = "# Native triplet SHA256: $nativeTripletHash`ninclude(`"$nativeTriplet`")`nif(CMAKE_HOST_WIN32)`n    set(PKGCONFIG `"$hostProgram`" CACHE FILEPATH `"Pinned Cemu host pkgconf`" FORCE)`n    list(APPEND VCPKG_PORT_CONFIGS `"$hostHook`")`nendif()`n"
    [IO.File]::WriteAllText((Join-Path $tripletRoot 'arm64-android.cmake'), $triplet, [Text.UTF8Encoding]::new($false))
}
function Prepare-Dependencies {
    $dependencies = & git -C $Destination submodule status --recursive
    if ($LASTEXITCODE -ne 0 -or ($dependencies | Where-Object { $_ -match '^[+-U]' })) {
        throw 'Cemu recursive dependency checkout does not match its pinned gitlinks'
    }
    # vcpkg extracts versioned ports with Git outside this script's -c scope.
    Invoke-Git -GitArgs @('-C', (Join-Path $Destination 'dependencies/vcpkg'), 'config', 'core.longpaths', 'true')
    # vcpkg requires a pre-existing files cache; all native outputs stay local.
    New-Item -ItemType Directory -Force -Path (Join-Path $VcpkgBuildRoot 'a') | Out-Null
    Prepare-HostPkgconf
    Prepare-HostMsys
}
$revision = (& git -C $Upstream rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $revision -ne $pin) { throw "Cemu upstream must be pinned to $pin" }
$marker = Join-Path $Destination '.calamansi-patch-hash'
$desktopSettings = Join-Path $Destination 'CMakeSettings.json'
if ((Test-Path $marker) -and (Get-Content -Raw $marker).Trim() -eq "$pin $actual") {
    $generatedRevision = (& git -C $Destination rev-parse HEAD).Trim()
    if ($LASTEXITCODE -ne 0 -or $generatedRevision -ne $pin) { throw 'Generated Cemu source pin changed' }
    Invoke-Git -GitArgs @('-C', $Destination, 'apply', '--reverse', '--check', $patch)
    Prepare-Dependencies
    # Visual Studio's BOM-prefixed desktop model is not an Android/AGP preset.
    if (Test-Path $desktopSettings) { Remove-Item -LiteralPath $desktopSettings }
    exit 0
}
# Destination is an owned generated source checkout, never the pinned gitlink.
if (Test-Path $Destination) {
    if ($env:OS -eq 'Windows_NT') {
        & cmd.exe /d /c "rd /s /q `"\\?\$Destination`""
        if ($LASTEXITCODE -ne 0) { throw 'Could not replace owned Cemu source checkout' }
    } else {
        Remove-Item -LiteralPath $Destination -Recurse -Force
    }
}
Invoke-Git -GitArgs @('-c', 'core.longpaths=true', 'clone', '--no-hardlinks', '--no-checkout', $Upstream, $Destination)
Invoke-Git -GitArgs @('-C', $Destination, 'checkout', '--detach', $pin)
# The selected fork's SSImco/ZArchive URL is gone. Exzap retains exactly the
# same c50b5d222bb599be9287664c835c2a820aa5342a object; no dependency pin changes.
Invoke-Git -GitArgs @('-C', $Destination, 'config', 'submodule.dependencies/ZArchive.url', 'https://github.com/Exzap/ZArchive.git')
Invoke-Git -GitArgs @('-C', $Destination, '-c', 'core.longpaths=true', 'submodule', 'update', '--init', '--recursive')
Prepare-Dependencies
Invoke-Git -GitArgs @('-C', $Destination, 'apply', '--check', $patch)
Invoke-Git -GitArgs @('-C', $Destination, 'apply', $patch)
if (Test-Path $desktopSettings) { Remove-Item -LiteralPath $desktopSettings }
# vcpkg's manifest baseline and overlay ports are supplied by this exact source.
Set-Content -NoNewline -Encoding ASCII $marker "$pin $actual"
