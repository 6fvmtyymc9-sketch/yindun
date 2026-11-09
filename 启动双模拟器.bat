@echo off
chcp 65001 >nul
title YinDun - Dual Emulator (elder + child)

rem ============================================================
rem  银盾双机演示环境
rem    左窗口 yindun30    -> 老人手机（银盾主 App）
rem    右窗口 yindun_child -> 子女手机（银盾家人）
rem
rem  两个窗口都要留着，关掉哪个哪台手机就没了。
rem  内存吃紧时先关掉 Android Studio，它一个人占 1.2G。
rem ============================================================

set "ANDROID_SDK_ROOT=D:\Android"
set "ANDROID_AVD_HOME=D:\Android\avd"
set "ADB=D:\Android\platform-tools\adb.exe"
cd /d "D:\Android\emulator"

echo ============================================
echo   银盾双机演示环境
echo   左：老人手机   右：子女手机
echo ============================================
echo.

echo [1/4] 启动老人手机 (emulator-5554) ...
start "老人手机 yindun30" emulator.exe -avd yindun30 -port 5554 -no-boot-anim -gpu off -netdelay none -netspeed full
timeout /t 8 >nul

echo [2/4] 启动子女手机 (emulator-5556) ...
start "子女手机 yindun_child" emulator.exe -avd yindun_child -port 5556 -no-boot-anim -gpu off -netdelay none -netspeed full

echo [3/4] 等两台开机（第一次开机要几分钟，别关窗口）...
"%ADB%" -s emulator-5554 wait-for-device
"%ADB%" -s emulator-5556 wait-for-device

echo [4/4] 关掉自动息屏（不然演示到一半屏幕会黑）...
for %%P in (5554 5556) do (
  "%ADB%" -s emulator-%%P shell input keyevent KEYCODE_WAKEUP >nul 2>&1
  "%ADB%" -s emulator-%%P shell svc power stayon true >nul 2>&1
  "%ADB%" -s emulator-%%P shell settings put system screen_off_timeout 2147483647 >nul 2>&1
)

echo.
echo 完成。两台手机都已就绪，屏幕不会自动熄灭。
echo 演示顺序见 docs\演示步骤.md
echo.
pause
