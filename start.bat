@echo off
rem Runs the server from the STABLE worktree (..\otb-chess-stable): a snapshot of the LATEST
rem COMMIT (approved code). In-progress, uncommitted edits in this working tree never reach
rem this server -- safe for manual testing while development continues here.
rem To run the working tree as-is (including in-progress code), use start-dev.bat instead.
cd /d %~dp0
for /f "delims=" %%i in ('git rev-parse HEAD') do set "OTB_STABLE_SHA=%%i"
git worktree prune
if not exist "..\otb-chess-stable\.git" (
  git worktree add --detach "..\otb-chess-stable" %OTB_STABLE_SHA% || exit /b 1
) else (
  git -C "..\otb-chess-stable" checkout --detach %OTB_STABLE_SHA% || exit /b 1
)
echo.
echo Serving commit %OTB_STABLE_SHA% from ..\otb-chess-stable
echo.
pushd "..\otb-chess-stable"
call mvn clean compile exec:java -Dexec.mainClass="io.github.dlbbld.otbchess.server.OtbChessServer"
popd
