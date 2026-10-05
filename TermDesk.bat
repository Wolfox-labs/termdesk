@echo off
rem TermDesk PC - manual launcher.
rem
rem Starts the agent on every interface with shell access, and brings up the
rem public address. If a cloudflared config already exists in %USERPROFILE%\.cloudflared
rem the address is the stable hostname from that file; otherwise cloudflared
rem hands out a temporary *.trycloudflare.com address that changes on restart.
rem
rem Close this window (or press Ctrl+C) to stop everything.
setlocal
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo.
  echo   Node.js was not found on PATH. Install Node 20 or newer, then run this again.
  echo.
  pause
  exit /b 1
)

rem Probing the ACP kernels costs one handshake each and is cached for ten
rem minutes, so the startup report can state what each kernel really supports.
set TERMDESK_KERNELS_PROBE=1

echo.
echo   Starting TermDesk PC agent...
echo.
node "pc-agent\src\server.js" --host 0.0.0.0 --enable-shell --tunnel %*

echo.
echo   Agent stopped.
pause
endlocal