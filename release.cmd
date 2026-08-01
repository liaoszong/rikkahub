@echo off
setlocal
cd /d "%~dp0"
where pwsh.exe >nul 2>nul
if errorlevel 1 (
  echo PowerShell 7 is required. Install it, then run this file again.
  pause
  exit /b 1
)
pwsh.exe -NoLogo -NoExit -ExecutionPolicy Bypass -File "%~dp0ops\release\release.ps1"
