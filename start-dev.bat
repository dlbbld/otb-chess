@echo off
rem Runs the server straight from THIS working tree -- including in-progress, uncommitted
rem changes. For testing only approved (committed) code use start.bat, which serves a stable
rem snapshot of the latest commit from the ..\otb-chess-stable worktree instead.
call mvn clean compile exec:java -Dexec.mainClass="io.github.dlbbld.otbchess.server.OtbChessServer"
