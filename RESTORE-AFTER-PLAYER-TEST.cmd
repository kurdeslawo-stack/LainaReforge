@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\restore-after-player-test.ps1" %*
exit /b %ERRORLEVEL%
