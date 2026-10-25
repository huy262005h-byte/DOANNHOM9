@echo off
chcp 65001 > nul
title Máy Khách Đồng Bộ Tệp Tin (Client)
echo Khởi chạy Máy Khách (Client)...
start "FileSyncClient" javaw -jar "target\DOANCS-1.0-SNAPSHOT.jar" --client
exit
