@echo off
setlocal

cd /d "%~dp0"

echo Stopping existing local services...
powershell -ExecutionPolicy Bypass -File "scripts\local\stop-local.ps1"

echo.
echo Initializing MySQL schema and seed data...
powershell -ExecutionPolicy Bypass -File "scripts\local\init-mysql.ps1"
if errorlevel 1 goto fail

echo.
echo Starting frontend and backend services...
powershell -ExecutionPolicy Bypass -File "scripts\local\start-local.ps1"
if errorlevel 1 goto fail

echo.
echo Waiting for services to warm up...
timeout /t 10 /nobreak >nul

start "" "http://127.0.0.1:5500/login.html"

echo.
echo Frontend: http://127.0.0.1:5500/login.html
echo Gateway:  http://127.0.0.1:8000/
echo.
echo If anything fails, check .local-runtime\*.log
goto end

:fail
echo.
echo Startup failed.
echo Check the console output and .local-runtime\*.log
echo.
pause
exit /b 1

:end
echo Press any key to close this window...
pause >nul
endlocal
