@REM Maven Wrapper for Windows - 使用项目内 .mvn/wrapper 中的 jar，无需本机安装 Maven
@echo off
setlocal

set "MAVEN_PROJECTBASEDIR=%~dp0"
cd /d "%MAVEN_PROJECTBASEDIR%"

if "%JAVA_HOME%"=="" (
  set "JAVACMD=java"
) else (
  set "JAVACMD=%JAVA_HOME%\bin\java"
)

set "WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar"
if not exist "%WRAPPER_JAR%" (
  echo Error: maven-wrapper.jar not found in .mvn\wrapper
  exit /b 1
)

"%JAVACMD%" -classpath "%WRAPPER_JAR%" "-Dmaven.multiModuleProjectDirectory=%MAVEN_PROJECTBASEDIR%" org.apache.maven.wrapper.MavenWrapperMain %*
