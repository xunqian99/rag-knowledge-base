@echo off
REM 端到端验证:一条命令跑完所有检查
chcp 65001 >nul

set PY=
where python >nul 2>nul && set PY=python
if "%PY%"=="" (where py >nul 2>nul && set PY=py)
if "%PY%"=="" (
  if exist "%USERPROFILE%\AppData\Roaming\uv\python\cpython-3.14.3-windows-x86_64-none\python.exe" (
    set PY="%USERPROFILE%\AppData\Roaming\uv\python\cpython-3.14.3-windows-x86_64-none\python.exe"
  )
)
if "%PY%"=="" (
  echo 找不到 Python,请先安装 Python 3,或者手动运行 tools\verify.py
  pause
  exit /b 1
)

%PY% "%~dp0tools\verify.py"
pause
