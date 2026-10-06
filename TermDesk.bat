@echo off
rem TermDesk PC - manual launcher.
rem
rem Starts the agent on every interface with shell access and dials out to the VPS
rem relay, which is the one address the phone uses. No tunnel and no hostname on
rem this machine: a hostname can belong to exactly one machine, and this one is
rem the relay's, so the phone always reaches the relay and the relay routes to
rem whichever computer it is paired with.
rem
rem A pairing code is printed at startup; open http://127.0.0.1:7420/pair for a
rem fresh one (each visit mints a new code) and http://127.0.0.1:7420/devices for
rem the phones paired to this computer.
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

rem The relay connector reads %USERPROFILE%\.termdesk\relay.json (url, nodeId, key).
set TERMDESK_RELAY=1

echo.
echo   Starting TermDesk PC agent...
echo.
node "pc-agent\src\server.js" --host 0.0.0.0 --enable-shell %*

echo.
echo   Agent stopped.
pause
endlocal