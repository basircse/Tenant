@echo off
rem Starts the TMS API (and the web console it serves) on a Windows server.
rem Settings: config\application.properties. Log: logs\tms-api.log. Close the window to stop it.
cd /d "%~dp0"
java -Xms256m -Xmx768m -jar tms-api.jar
