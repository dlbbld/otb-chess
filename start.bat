@echo off
call mvn clean compile exec:java -Dexec.mainClass="com.dlb.chess.dumbboard.server.DumbChessboardServer"
