@echo off
setlocal enabledelayedexpansion
title Headwind Remote Control - GitHub Publisher

echo ======================================================================
echo    Headwind Remote Control - GitHub Publisher
echo ======================================================================
echo.

REM 1. Check for git
where git >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Git is not installed or not in your PATH.
    echo Please install Git from https://git-scm.com/ and try again.
    pause
    exit /b 1
)

REM 2. Ensure secrets.key is in .gitignore
findstr /i /c:"secrets.key" .gitignore >nul 2>&1
if %errorlevel% neq 0 (
    echo secrets.key>> .gitignore
    echo [INFO] Added secrets.key to .gitignore for security.
)

REM 3. Initialize git if needed
if not exist ".git" (
    echo [INFO] Initializing new Git repository...
    git init -b main
    if %errorlevel% neq 0 (
        git init
        git branch -M main
    )
)

REM 4. Check remote origin
git remote get-url origin >nul 2>&1
if %errorlevel% neq 0 (
    echo.
    echo [SETUP] No GitHub remote repository configured yet.
    set /p REPO_URL="Enter your GitHub repository URL (e.g. https://github.com/USER/headwind_remote_control.git): "
    if "!REPO_URL!"=="" (
        echo [ERROR] Repository URL cannot be empty.
        pause
        exit /b 1
    )
    git remote add origin !REPO_URL!
    echo [OK] Added remote origin: !REPO_URL!
)

REM 5. Prompt for commit message
echo.
set /p COMMIT_MSG="Enter commit message [Release / Update Headwind Remote Control plugin]: "
if "!COMMIT_MSG!"=="" set COMMIT_MSG=Release / Update Headwind Remote Control plugin

REM 6. Add, commit, and push
echo.
echo [INFO] Staging files...
git add .

echo [INFO] Committing changes...
git commit -m "!COMMIT_MSG!"

echo.
echo [INFO] Pushing to GitHub (main branch)...
git push -u origin main

if %errorlevel% equ 0 (
    echo.
    echo ======================================================================
    echo    [SUCCESS] Repository successfully pushed to GitHub!
    echo ======================================================================
) else (
    echo.
    echo [WARN] Push failed. If the remote repository has commits, try running:
    echo        git pull --rebase origin main
    echo        git push -u origin main
)

echo.
pause
