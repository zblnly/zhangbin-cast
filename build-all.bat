@echo off
chcp 65001 >nul
title 张斌的手机投屏 — 一键编译 APK
echo ═══════════════════════════════════════════
echo   张斌的手机投屏 — 一键编译脚本
echo ═══════════════════════════════════════════
echo.
echo   💡 此脚本在你的电脑上运行
echo   前提: 已经安装 Android Studio
echo.

:: 检测 Java
echo [1/4] 检测 Java…
java -version >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo   ❌ 未找到 Java JDK
    echo   建议: 先安装 Android Studio（自带 JDK）
    echo   或手动安装: https://adoptium.net
    pause
    exit /b 1
)
java -version 2>&1 | findstr "version" | findstr "17\|11\|21" >nul
if %ERRORLEVEL% NEQ 0 (
    echo   ⚠️ 推荐 JDK 17，当前版本可能不兼容
    java -version 2>&1
)
echo   ✓ Java 就绪

:: 检测 Gradle
echo [2/4] 检测 Gradle Wrapper…
if exist "%~dp0phone-app\gradlew.bat" (
    echo   ✓ phone-app Gradle Wrapper 就绪
) else (
    echo   ❌ phone-app Gradle Wrapper 缺失
    pause
    exit /b 1
)
if exist "%~dp0tv-app\gradlew.bat" (
    echo   ✓ tv-app Gradle Wrapper 就绪
) else (
    echo   ❌ tv-app Gradle Wrapper 缺失
    pause
    exit /b 1
)

:: 编译手机端
echo [3/4] 编译手机端 APK…
cd /d "%~dp0phone-app"
call gradlew.bat assembleDebug --no-daemon -q
if %ERRORLEVEL% NEQ 0 (
    echo   ❌ 手机端编译失败，详细信息:
    call gradlew.bat assembleDebug --no-daemon 2>&1
    pause
    exit /b 1
)
echo   ✓ 手机端 APK 编译成功！

:: 编译电视端
echo [4/4] 编译电视端 APK…
cd /d "%~dp0tv-app"
call gradlew.bat assembleDebug --no-daemon -q
if %ERRORLEVEL% NEQ 0 (
    echo   ❌ 电视端编译失败，详细信息:
    call gradlew.bat assembleDebug --no-daemon 2>&1
    pause
    exit /b 1
)
echo   ✓ 电视端 APK 编译成功！

echo.
echo ═══════════════════════════════════════════
echo   ✅ 全部编译完成！
echo ═══════════════════════════════════════════
echo.
echo   📱 手机端 APK:
echo      %~dp0phone-app\app\build\outputs\apk\debug\app-debug.apk
echo.
echo   📺 电视端 APK:
echo      %~dp0tv-app\app\build\outputs\apk\debug\app-debug.apk
echo.
echo   用 adb 安装:
echo     adb install "%~dp0phone-app\app\build\outputs\apk\debug\app-debug.apk"
echo     adb install "%~dp0tv-app\app\build\outputs\apk\debug\app-debug.apk"
echo.
pause
