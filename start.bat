@echo off
rem Fetches the latest pushed codex/further-hardening commit and serves it from the
rem STABLE worktree (..\otb-chess-stable). Test here before merging the branch's PR.
rem In-progress, uncommitted edits in this working tree never reach this server.
rem To run the working tree as-is (including in-progress code), use start-dev.bat instead.
setlocal
cd /d "%~dp0" || exit /b 1
git fetch origin codex/further-hardening || exit /b 1
set "OTB_STABLE_SHA="
for /f "delims=" %%i in ('git rev-parse --verify FETCH_HEAD') do set "OTB_STABLE_SHA=%%i"
if not defined OTB_STABLE_SHA exit /b 1
git worktree prune || exit /b 1
if not exist "..\otb-chess-stable\.git" (
  git worktree add --detach "..\otb-chess-stable" %OTB_STABLE_SHA% || exit /b 1
) else (
  git -C "..\otb-chess-stable" checkout --detach %OTB_STABLE_SHA% || exit /b 1
)
echo.
echo Serving codex/further-hardening commit %OTB_STABLE_SHA% from ..\otb-chess-stable
echo.
pushd "..\otb-chess-stable" || exit /b 1
call mvn clean compile exec:java -Dexec.mainClass="io.github.dlbbld.otbchess.server.OtbChessServer"
set "OTB_SERVER_EXIT=%ERRORLEVEL%"
popd
exit /b %OTB_SERVER_EXIT%
