@echo off
setlocal
where python >nul 2>nul
if %errorlevel%==0 (
  python "%~dp0plugin.py"
) else (
  py "%~dp0plugin.py"
)
