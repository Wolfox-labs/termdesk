@echo off
rem TermDesk desktop - development launcher for the graphical window.
rem
rem The shipping form is an installer built with "gradlew packageMsi"; until then
rem this starts exactly the same window that installer will carry.
rem
rem   TermDesk-Desktop.bat          just open the window
rem   TermDesk-Desktop.bat -start   open the window and bring the agent up with it
setlocal
cd /d "%~dp0"

if not defined JAVA_HOME if exist "C:\Program Files\Java\jdk-21" set "JAVA_HOME=C:\Program Files\Java\jdk-21"

rem Off unless asked for: nothing on this machine should start by itself.
if /i "%~1"=="-start" set "TERMDESK_AUTOSTART=1"

call gradlew.bat run --console=plain
endlocal