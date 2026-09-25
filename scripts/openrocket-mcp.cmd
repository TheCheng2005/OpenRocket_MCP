@echo off
rem Builds (first run only) and starts the OpenRocket MCP server over stdio (Windows).
setlocal
set "ROOT=%~dp0.."
set "BIN=%ROOT%\build\install\openrocket-mcp\bin\openrocket-mcp.bat"
where java >nul 2>nul
if errorlevel 1 if not defined JAVA_HOME (
  echo openrocket-mcp: Java 17 or newer is required ^(https://adoptium.net^). 1>&2
  exit /b 1
)
if not exist "%BIN%" (
  echo openrocket-mcp: building ^(first run can take a minute^)... 1>&2
  pushd "%ROOT%"
  call gradlew.bat -q installDist 1>&2
  if errorlevel 1 exit /b 1
  popd
)
call "%BIN%" %*
