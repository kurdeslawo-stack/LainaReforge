@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\prepare-player-test.ps1" %*
exit /b %ERRORLEVEL%
