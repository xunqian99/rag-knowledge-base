@echo off
REM 双击这个文件就能启动整个系统
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0start.ps1"
pause
