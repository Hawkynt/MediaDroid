@echo off
rem MediaDroyd, portable: settings stay in the data folder beside this file.
start "" "%~dp0runtime\bin\javaw.exe" -Xmx2g -Dcompose.application.resources.dir="%~dp0lib\resources" -cp "%~dp0lib\*" com.hawkynt.mediadroyd.desktop.MainKt %*
