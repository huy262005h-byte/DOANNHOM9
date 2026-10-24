@echo off
chcp 65001 > nul
title Máy Chủ Đồng Bộ Tệp Tin (Server)
echo Khởi chạy Máy Chủ (Server)...
start "FileSyncServer" javaw -jar "target\DOANCS-1.0-SNAPSHOT.jar" --server
exit
