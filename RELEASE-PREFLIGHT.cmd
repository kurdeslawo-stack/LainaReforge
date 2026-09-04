@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\release-preflight.ps1" %*
exit /b %ERRORLEVEL%
