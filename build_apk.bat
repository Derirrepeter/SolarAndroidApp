@echo off
rem Baut die Android-App (APK) mit dem Java aus Android Studio.
rem Ergebnis: SolarUebersicht.apk in diesem Ordner
cd /d "%~dp0"
set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo Android Studio nicht gefunden unter %JAVA_HOME%
    pause
    exit /b 1
)
rem Zwischendateien liegen unter %USERPROFILE%\.solaruebersicht-build (nicht in Synology Drive)
call "%~dp0gradlew.bat" assembleDebug --project-cache-dir "%USERPROFILE%\.solaruebersicht-build\gradle-cache" || goto :fehler
copy /y "%USERPROFILE%\.solaruebersicht-build\app\outputs\apk\debug\app-debug.apk" SolarUebersicht.apk >nul
echo.
echo Fertig: SolarUebersicht.apk
pause
exit /b 0
:fehler
echo.
echo FEHLER beim Bauen - siehe Meldungen oben.
pause
exit /b 1
