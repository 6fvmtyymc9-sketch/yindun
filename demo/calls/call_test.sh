#!/bin/bash
# 模拟一通陌生来电：响铃 → 接听 → 聊几秒 → 挂断 → 把演示录音放进荣耀的录音目录
# 用法：bash demo/calls/call_test.sh 13900001111 demo/calls/13900001111_20261109103015.mp3 [通话秒数]
A="adb"   # 多台设备时改成 adb -s emulator-5554
NUM="$1"; MP3="$2"; SEC="${3:-8}"; DIR=/sdcard/Sounds/CallRecord
$A emu gsm call $NUM >/dev/null; sleep 3
$A emu gsm accept $NUM >/dev/null; sleep "$SEC"
$A emu gsm cancel $NUM >/dev/null; sleep 1
$A push "$MP3" "${MP3%.mp3}.txt" $DIR/ >/dev/null
# adb push 保留了 Mac 上的旧修改时间；真机上录音是通话中写的，这里补一下时间
$A shell "touch $DIR/$(basename "$MP3") $DIR/$(basename "${MP3%.mp3}.txt")"
echo "call $NUM done, recording pushed"
