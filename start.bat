@echo off
REM dumb-chessboard targets Java 26 (maven.compiler.release=26 in pom.xml). The
REM default JDK on PATH may be older, in which case `exec:java` cannot launch the
REM compiled classes and fails with:
REM   "class file version 70.0 ... only recognizes class file versions up to 61.0".
REM Pin JAVA_HOME to a locally installed JDK 26 so compile and run both use it.
setlocal
set "JAVA_HOME="
for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-26*") do set "JAVA_HOME=%%D"
if not defined JAVA_HOME (
  echo [start.bat] No JDK 26 found under "C:\Program Files\Eclipse Adoptium".
  echo [start.bat] Install JDK 26, or edit start.bat to point JAVA_HOME at your JDK 26+.
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo [start.bat] Using JAVA_HOME=%JAVA_HOME%
call mvn compile exec:java -Dexec.mainClass="com.dlb.chess.dumbboard.server.DumbChessboardServer"
endlocal
