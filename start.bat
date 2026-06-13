@echo off
call mvn compile exec:java -Dexec.mainClass="com.dlb.chess.dumbboard.server.DumbChessboardServer"
