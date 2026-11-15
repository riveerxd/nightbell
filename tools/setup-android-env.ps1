# Nightbell 中文版：本地编译 / 调试环境安装器（默认装到 D:\Android，可用 -Root 指定）
#
#   powershell -ExecutionPolicy Bypass -File tools\setup-android-env.ps1
#       仅命令行编译环境（Temurin JDK 21 + Android SDK）
#
#   powershell -ExecutionPolicy Bypass -File tools\setup-android-env.ps1 -WithStudio
#       再加 Android Studio 绿色版：Compose 预览、断点、真机 Run
#       注意：这版 Studio 自带 JBR 25，Gradle 8.13 不支持；脚本会单独装 JDK 21
#       并把项目的 Gradle JDK 固定在 21（见第 4.5 步）。
#
#   powershell -ExecutionPolicy Bypass -File tools\setup-android-env.ps1 -UseStudioJbr
#       兼容参数（已无实际作用）：JDK 21 一律单独安装，因为 Studio 自带 JBR 是 25。
#
# 可选：-StudioZip <已下载的 zip>、-NoPermanentEnv、-Mirror <SDK 镜像地址>
#
# 建议把 IDE / SDK / Gradle 缓存放在同一个磁盘上，避免系统盘空间不足；
# 项目目录用 -Project 指定，默认取本脚本所在目录的上一级。
param(
    [switch]$WithStudio,
    [switch]$UseStudioJbr,
    [string]$StudioZip = '',
    [switch]$NoPermanentEnv,
    [string]$Mirror = '',
    [string]$Root = 'D:\Android',
    [string]$Project = '',
    [string]$JdkUrl = 'https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse'
)

$ErrorActionPreference = 'Stop'
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not $Project) { $Project = Split-Path -Parent $PSScriptRoot }
$Jdk     = Join-Path $Root 'jdk-21'
$Sdk     = Join-Path $Root 'Sdk'
$Cache   = Join-Path $Root 'gradle-home'
$Tmp     = Join-Path $Root '_downloads'
$Proj    = $Project
$Studio  = Join-Path $Root 'Android Studio'
$Jbr     = Join-Path $Studio 'jbr'
$Scfg    = Join-Path $Root 'studio-config'
$ToolsZip  = Join-Path $Tmp 'commandlinetools-win.zip'
$JdkZip    = Join-Path $Tmp 'jdk21.zip'
$StudioZipLocal = Join-Path $Tmp 'android-studio-windows.zip'
$Curl = Join-Path $env:WINDIR 'System32\curl.exe'

# 官方稳定版 Rabbit 2 | 2026.2.2 Patch 1，ZIP 1.5GB，SHA-256 取自 developer.android.com/studio
$StudioUrl = 'https://edgedl.me.gvt1.com/android/studio/ide-zips/2026.2.2.9/android-studio-rabbit2-patch1-windows.zip'
$StudioSha = '6813b0ce669844b87c5d56b9c5f661362bd3bebff55327415496bd7457a7b917'

foreach ($d in @($Root, $Sdk, $Cache, $Tmp)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d -Force | Out-Null }
}
Write-Host "Target: $Root   Project: $Proj"
Write-Host ""

function Get-File([string]$Url, [string]$Out) {
    if (Test-Path $Out) {
        Write-Host "      已有 $(Split-Path $Out -Leaf)，跳过下载（删除该文件可重新下）"
        return
    }
    Write-Host "      $Url"
    & $Curl -L --fail --retry 3 --retry-delay 2 --max-time 1800 -o $Out $Url
    if ($LASTEXITCODE -ne 0) { throw "下载失败: $Url" }
}

function Expand-Zip([string]$Zip, [string]$Dest) {
    if (Test-Path $Dest) { Remove-Item $Dest -Recurse -Force }
    New-Item -ItemType Directory -Path $Dest -Force | Out-Null
    [System.IO.Compression.ZipFile]::ExtractToDirectory($Zip, $Dest)
}

# ---------------------------------------------------------------- 1. Studio
$studioPresent = Test-Path (Join-Path $Studio 'bin\studio64.exe')
if ($WithStudio -and -not $studioPresent) {
    Write-Host '[1/5] 安装 Android Studio 绿色版 ...'
    if ($StudioZip -ne '') {
        if (-not (Test-Path $StudioZip)) { throw "找不到 -StudioZip: $StudioZip" }
        Copy-Item -Path $StudioZip -Destination $StudioZipLocal -Force
    } else {
        Get-File $StudioUrl $StudioZipLocal
    }
    $hash = (Get-FileHash $StudioZipLocal -Algorithm SHA256).Hash.ToLower()
    if ($hash -ne $StudioSha) {
        throw "ZIP 校验和不匹配，已停止。`n  实测 $hash`n  官方 $StudioSha"
    }
    Write-Host "      SHA-256 校验通过"
    $stage = Join-Path $Tmp 'studio-stage'
    Expand-Zip $StudioZipLocal $stage
    $inner = Get-ChildItem -Path $stage -Directory | Where-Object { Test-Path (Join-Path $_.FullName 'bin\studio64.exe') } | Select-Object -First 1
    if ($null -eq $inner) { $inner = Get-ChildItem -Path $stage -Directory | Select-Object -First 1 }
    if ($null -eq $inner) { throw 'ZIP 里没有找到 android-studio 目录' }
    Move-Item -Path $inner.FullName -Destination $Studio -Force
    Remove-Item $stage -Recurse -Force
    $studioPresent = $true
    Write-Host "      IDE -> $Studio"
} else {
    Write-Host '[1/5] Android Studio：' -NoNewline
    if ($studioPresent) { Write-Host '已存在，跳过' } else { Write-Host '未安装（仅命令行编译模式）' }
}

# ---------------------------------------------------------------- 2. JDK
# 2026-11-15 实测：Android Studio Rabbit 2 (2026.2.2) 自带的是 JBR **25**，不是 21。
# Gradle 8.13 不支持在 JDK 25 上运行（Java 24 支持到 Gradle 8.14，25 要到 Gradle 9.1），
# 所以命令行构建必须单独装 Temurin JDK 21。IDE 自身用 JBR 25 没问题，
# 只需在 Studio 的 Gradle JDK 设置里选脚本装好的 JDK 21（即下面的 $Jdk）。
# -UseStudioJbr 保留为兼容参数，但不再参与 JAVA_HOME 选择。
$JavaHome = $null
if (Test-Path (Join-Path $Jdk 'bin\java.exe')) {
    $JavaHome = $Jdk
    Write-Host "[2/5] JDK 21 已存在，跳过：$JavaHome"
} else {
    Write-Host '[2/5] 下载 Temurin JDK 21（Gradle 8.13 需要，不能用 Studio 的 JBR 25）...'
    Get-File $JdkUrl $JdkZip
    $stage = Join-Path $Tmp 'jdk-stage'
    Expand-Zip $JdkZip $stage
    $inner = Get-ChildItem -Path $stage -Directory | Select-Object -First 1
    if ($null -eq $inner) { throw 'JDK ZIP 里没有找到 jdk-* 目录' }
    Move-Item -Path $inner.FullName -Destination $Jdk -Force
    Remove-Item $stage -Recurse -Force
    $JavaHome = $Jdk
    Write-Host "      JDK -> $JavaHome"
}
if ($null -eq $JavaHome) { throw '未能确定 JAVA_HOME' }
# 不要执行 `java -version`：PS 5.1 在 ErrorActionPreference=Stop 下会把原生命令的
# stderr（java 的版本信息就写在 stderr）变成终止错误，2>$null 也拦不住。
# 直接读 JDK 自带的 release 文件，纯 PowerShell，无原生调用。
$releaseFile = Join-Path $JavaHome 'release'
if (Test-Path $releaseFile) {
    $m = [regex]::Match((Get-Content $releaseFile -Raw), 'JAVA_VERSION="([^"]+)"')
    if ($m.Success) { Write-Host "      版本：$($m.Groups[1].Value)" }
}

# ---------------------------------------------------------------- 3. cmdline-tools
$toolsBin = Join-Path $Sdk 'cmdline-tools\latest\bin'
if (-not (Test-Path (Join-Path $toolsBin 'sdkmanager.bat'))) {
    Write-Host '[3/5] 下载 Android 命令行工具 ...'
    # 不写死版本号：从官方 repository2-1.xml 解析最新 commandlinetools-win-*_latest.zip
    $repoXml = Join-Path $Tmp 'repository2-1.xml'
    $ver = 11076708
    try {
        & $Curl -sSL --fail --max-time 60 -o $repoXml 'https://dl.google.com/android/repository/repository2-1.xml'
        $xml = Get-Content $repoXml -Raw
        $nums = [regex]::Matches($xml, 'commandlinetools-win-(\d+)_latest\.zip') |
                ForEach-Object { [int]$_.Groups[1].Value } | Sort-Object -Descending
        if ($nums) { $ver = @($nums)[0] }
    } catch { Write-Host '      仓库索引读取失败，回退到已知可用版本' }
    $fileName = "commandlinetools-win-${ver}_latest.zip"
    Write-Host "      版本：$fileName"

    $candidates = @()
    if ($Mirror -ne '') { $candidates += ($Mirror.TrimEnd('/') + '/' + $fileName) }
    $candidates += ('https://dl.google.com/android/repository/' + $fileName)
    $chosen = $null
    foreach ($u in $candidates) {
        try {
            $r = Invoke-WebRequest -Uri $u -Method Head -UseBasicParsing -TimeoutSec 20
            if ($r.StatusCode -eq 200) { $chosen = $u; break }
        } catch { Write-Host "      镜像不可达，回退官方：$u" }
    }
    if ($null -eq $chosen) { throw '命令行工具下载地址全部不可用' }
    Get-File $chosen $ToolsZip
    $stage = Join-Path $Tmp 'cmdline-stage'
    Expand-Zip $ToolsZip $stage
    $dest = Join-Path $Sdk 'cmdline-tools\latest'
    if (-not (Test-Path $dest)) { New-Item -ItemType Directory -Path $dest -Force | Out-Null }
    $inner = Join-Path $stage 'cmdline-tools'
    if (-not (Test-Path $inner)) { $inner = Get-ChildItem -Path $stage -Directory | Select-Object -First 1; $inner = $inner.FullName }
    Copy-Item -Path (Join-Path $inner '*') -Destination $dest -Recurse -Force
    Remove-Item $stage -Recurse -Force
    Write-Host "      sdkmanager -> $toolsBin"
} else {
    Write-Host '[3/5] 命令行工具已存在，跳过'
}

# ---------------------------------------------------------------- 4. 环境变量 / 项目配置
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $Sdk
$env:ANDROID_SDK_ROOT = $Sdk
$env:GRADLE_USER_HOME = $Cache
$env:PATH = "$JavaHome\bin;$Sdk\platform-tools;$env:PATH"

if (-not $NoPermanentEnv) {
    Write-Host '[4/5] 写入用户级环境变量 + 项目 local.properties ...'
    [Environment]::SetEnvironmentVariable('JAVA_HOME', $JavaHome, 'User')
    [Environment]::SetEnvironmentVariable('ANDROID_HOME', $Sdk, 'User')
    [Environment]::SetEnvironmentVariable('ANDROID_SDK_ROOT', $Sdk, 'User')
    [Environment]::SetEnvironmentVariable('GRADLE_USER_HOME', $Cache, 'User')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    if ($null -eq $userPath) { $userPath = '' }
    if ($userPath -notlike '*Android\Sdk\platform-tools*') {
        $parts = @($userPath.TrimEnd(';'), "$JavaHome\bin", "$Sdk\platform-tools") | Where-Object { $_ -ne '' }
        [Environment]::SetEnvironmentVariable('Path', ($parts -join ';'), 'User')
    }
} else {
    Write-Host '[4/5] 跳过永久环境变量（-NoPermanentEnv）'
}

$localProps = Join-Path $Proj 'local.properties'
if (-not (Test-Path $localProps)) {
    $sdkFwd = $Sdk -replace '\\', '/'
    Set-Content -Path $localProps -Value "sdk.dir=$sdkFwd" -Encoding ASCII
    Write-Host "      已写 $localProps"
}

if ($studioPresent) {
    if (-not (Test-Path $Scfg)) { New-Item -ItemType Directory -Path $Scfg -Force | Out-Null }
    # 旧判断 -notmatch 'idea\.config\.path' 会被 idea.properties 里注释掉的
    # "# idea.config.path=..." 骗中，导致重定向从未生效。只认行首的有效赋值。
    $idea = Join-Path $Studio 'bin\idea.properties'
    $existingIde = Get-ChildItem (Join-Path $env:APPDATA 'Google') -Directory -Filter 'AndroidStudio*' -ErrorAction SilentlyContinue |
        Where-Object { Test-Path (Join-Path $_.FullName 'options') } | Select-Object -First 1
    if (Test-Path $idea) {
        $txt = Get-Content $idea -Raw
        $hasRedirect = $txt -match '(?m)^\s*idea\.config\.path\s*='
        if (-not $hasRedirect -and $null -eq $existingIde) {
            $cfgFwd = $Scfg -replace '\\', '/'
            Add-Content -Path $idea -Value "`nidea.config.path=$cfgFwd/config`nidea.system.path=$cfgFwd/system"
            Write-Host "      idea.properties：config/system 已重定向到 $Scfg"
        } elseif ($null -ne $existingIde) {
            # IDE 已有配置目录时不做迁移：改重定向会让 IDE 变回全新安装，丢掉 SDK 路径等设置。
            Write-Host "      保留已有 IDE 配置目录：$($existingIde.FullName)（不迁移，避免清空现有设置）"
        }
    }
    $vmDir = Join-Path $Scfg 'config'
    if (-not (Test-Path $vmDir)) { New-Item -ItemType Directory -Path $vmDir -Force | Out-Null }
    $vm = Join-Path $vmDir 'studio64.exe.vmoptions'
    if (-not (Test-Path $vm)) {
        Set-Content -Path $vm -Value @('-Xms512m', '-Xmx3072m', '-XX:ReservedCodeCacheSize=512m', '-Dfile.encoding=UTF-8') -Encoding ASCII
        Write-Host '      VM options 模板已生成（仅模板：自定义 vmoptions 会整体替换默认参数，不自动启用）'
    }
}

# ---------------------------------------------------------------- 4.5 项目 Gradle JDK
# 报错 "Incompatible Gradle JVM version ... 25" 的根治：Gradle 层强制 JDK 21。
# IDE 层由 .idea/gradle.xml 的 gradleJvm=#GRADLE_LOCAL_JAVA_HOME 读取下面这行，
# 该文件随项目走（.gitignore 已放行），无需在 Studio 设置里手动点选。
Write-Host '[4.5/5] 固定项目 Gradle JDK 为 21 ...'
$props = Join-Path $Proj 'gradle.properties'
$javaHomeProp = 'org.gradle.java.home=' + ($JavaHome -replace '\\', '/')
$propText = Get-Content $props -Raw
if ($propText -match '(?m)^\s*org\.gradle\.java\.home\s*=') {
    $propText = [regex]::Replace($propText, '(?m)^\s*org\.gradle\.java\.home\s*=.*$', $javaHomeProp)
} else {
    $propText = $propText.TrimEnd() + "`n" + $javaHomeProp + "`n"
}
[IO.File]::WriteAllText($props, $propText, (New-Object Text.UTF8Encoding($false)))
Write-Host "      $javaHomeProp"

# ---------------------------------------------------------------- 5. SDK 包
# 2026-11-15 实测：commandlinetools-win-16111833 起，sdkmanager.bat 只是兼容包装，
# 会把 "platforms;android-36" 的分号改成斜杠再转交新的 android.exe，旧写法会报
# "Package platforms not found"。新版 CLI 包名本身就是斜杠格式，且不再需要 --licenses。
# 这里按实际存在的工具分两条路，并以「目录是否落盘」作为唯一成功判据（CLI 收尾
# 清理 .sdk\arch 缓存时可能抛 AccessDeniedException，但包其实已经装好）。
Write-Host '[5/5] 安装 platform 36 / build-tools 36.0.0 / platform-tools ...'
$androidCli = Join-Path $toolsBin 'android.exe'
$sdkmgr     = Join-Path $toolsBin 'sdkmanager.bat'
# 原生命令（java/android）会把进度和警告写到 stderr；PS 5.1 会把它包成
# ErrorRecord，在脚本顶部的 Stop 策略下直接终止。这里临时降级为 Continue。
$prevEap = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
if (Test-Path $androidCli) {
    Write-Host '      使用新版 Android CLI (android sdk install)'
    & $androidCli sdk install 'platforms/android-36' 'build-tools/36.0.0' 'platform-tools' "--sdk=$Sdk" 2>&1 | Out-Null
} else {
    Write-Host '      使用旧版 sdkmanager'
    @('y','y','y','y','y','y','y','y') | & $sdkmgr "--sdk_root=$Sdk" --licenses | Out-Null
    & $sdkmgr "--sdk_root=$Sdk" 'platforms;android-36' 'build-tools;36.0.0' 'platform-tools' | Out-Null
}
$ErrorActionPreference = $prevEap

# 新版 CLI 的 GC 阶段会抛 AccessDeniedException 并以非 0 退出，不能只看 $LASTEXITCODE。
# 真正需要的是这三个目录存在且含关键文件。
$mustExist = @(
    (Join-Path $Sdk 'platforms\android-36\android.jar'),
    (Join-Path $Sdk 'build-tools\36.0.0\aapt2.exe'),
    (Join-Path $Sdk 'platform-tools\adb.exe')
)
$missing = @($mustExist | Where-Object { -not (Test-Path $_) })
if ($missing.Count -gt 0) {
    throw "SDK 组件未装全，缺少：`n  $($missing -join "`n  ")"
}
Write-Host '      SDK 组件校验通过（android.jar / aapt2.exe / adb.exe）'

Write-Host ''
Write-Host '================ 环境就绪 ================'
Write-Host "JAVA_HOME       = $JavaHome"
Write-Host "ANDROID_HOME    = $Sdk"
Write-Host "GRADLE_USER_HOME= $Cache"
Write-Host "项目            = $Proj"
if ($studioPresent) {
    Write-Host ''
    Write-Host '首次启动 Android Studio（重开一个终端让环境变量生效）：'
    Write-Host "  $Studio\bin\studio64.exe"
    Write-Host "  1) 设置向导里 Android SDK Location 选 $Sdk（不要让它下到系统盘）"
    Write-Host "  2) Settings > Build Tools > Gradle > Gradle JDK 选 $Jdk"
    Write-Host '     （IDE 自带 JBR 是 25，Gradle 8.13 不支持，别选它）'
    Write-Host '  3) 在项目根目录 Run 到 USB 真机；模拟器在本机 CPU/虚拟化条件下不建议'
} else {
    Write-Host ''
    Write-Host '命令行出包：  build-zh.bat（项目根目录）'
    Write-Host '想加 IDE 调试：再加 -WithStudio 重跑本脚本'
}
Write-Host '=========================================='
