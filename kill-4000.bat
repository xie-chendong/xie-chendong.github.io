@echo off
chcp 65001 >nul
set PORT=4000

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%PORT% " ^| findstr LISTENING') do (
    echo 发现端口%PORT%占用进程PID: %%a
    taskkill /F /PID %%a
    echo 已强制结束 PID=%%a
)

echo.
echo 执行完毕，按任意键关闭
pause >nul
