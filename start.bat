@echo off
chcp 65001 >nul
REM NAVIGATOR botini ishga tushirish (Windows)
cd /d "%~dp0"
where python >nul 2>nul || (echo Python topilmadi. https://www.python.org/downloads/ dan o'rnating ^(Add to PATH belgisini qo'ying^). & pause & exit /b 1)
if not exist .venv (
  echo Virtual muhit yaratilmoqda...
  python -m venv .venv
)
.venv\Scripts\python -m pip install -q --upgrade pip
.venv\Scripts\python -m pip install -q -r requirements.txt
if not exist .env (
  set /p TOKEN="@BotFather dan olingan BOT_TOKEN ni kiriting: "
  call :mkenv
)
echo Bot ishga tushdi. To'xtatish uchun Ctrl+C.
.venv\Scripts\python -m navigator
pause
exit /b

:mkenv
setlocal EnableDelayedExpansion
(for /f "usebackq delims=" %%L in (".env.example") do (
  set "line=%%L"
  if "!line:~0,10!"=="BOT_TOKEN=" (echo BOT_TOKEN=%TOKEN%) else (echo %%L)
)) > .env
echo Token .env fayliga saqlandi
endlocal
exit /b
