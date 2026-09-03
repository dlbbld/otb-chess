@echo off
rem Runs the server straight from THIS working tree -- including in-progress, uncommitted
rem changes. For testing the latest pushed codex/further-hardening commit use start.bat,
rem which fetches that branch and serves it from the ..\otb-chess-stable worktree instead.
call mvn clean compile exec:java -Dexec.mainClass="io.github.dlbbld.otbchess.server.OtbChessServer"
