@echo off
setlocal
cd /d "%~dp0"
java --module-path javafx --add-modules javafx.controls --enable-native-access=javafx.graphics -cp "nfd-desktop.jar;lib/*" cn.qaiu.nfd.desktop.DesktopApp %*
