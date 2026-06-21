@echo off
call mvn clean compile exec:java -Dexec.mainClass="io.github.dlbbld.otbchess.server.OtbChessServer"
