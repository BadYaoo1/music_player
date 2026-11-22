@echo off
rem 进入脚本所在目录，避免依赖本机绝对路径
cd /d "%~dp0"
call mvn clean javafx:run
