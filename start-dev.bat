@echo off
rem Runs the server straight from THIS working tree -- including in-progress, uncommitted
rem changes. For testing the latest pushed commit of the testing branch use start.bat,
rem which fetches that branch (OTB_TEST_BRANCH, else its first argument, else the default)
rem and serves it from the ..\otb-chess-stable worktree instead.
call mvn clean compile exec:java -Dexec.mainClass="io.github.dlbbld.otbchess.server.OtbChessServer"
