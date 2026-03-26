@echo off
REM MyChart Patient Mobile App - Windows Installation Script
REM This script sets up the development environment and installs all dependencies

echo.
echo 🏥 MyChart Patient Mobile App - Windows Installation Script
echo ===========================================================
echo.

REM Check if Node.js is installed
echo [INFO] Checking Node.js installation...
node --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Node.js is not installed
    echo [INFO] Please install Node.js from: https://nodejs.org/
    pause
    exit /b 1
) else (
    for /f "tokens=*" %%i in ('node --version') do set NODE_VERSION=%%i
    echo [SUCCESS] Node.js is installed: %NODE_VERSION%
)

REM Check if npm is installed
echo [INFO] Checking npm installation...
npm --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] npm is not installed
    pause
    exit /b 1
) else (
    for /f "tokens=*" %%i in ('npm --version') do set NPM_VERSION=%%i
    echo [SUCCESS] npm is installed: %NPM_VERSION%
)

REM Check if pnpm is available (optional)
echo [INFO] Checking for pnpm (optional but recommended)...
pnpm --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARNING] pnpm is not installed. Using npm instead.
    echo [INFO] To install pnpm for faster builds: npm install -g pnpm
    set USE_PNPM=false
) else (
    for /f "tokens=*" %%i in ('pnpm --version') do set PNPM_VERSION=%%i
    echo [SUCCESS] pnpm is available: %PNPM_VERSION%
    set USE_PNPM=true
)

REM Create necessary directories
echo [INFO] Creating necessary directories...
if not exist "screenshots" mkdir screenshots
if not exist "logs" mkdir logs
echo [SUCCESS] Directories created successfully!

REM Set up environment
echo [INFO] Setting up development environment...
if not exist ".env.local" (
    (
        echo # Development Environment Variables
        echo VITE_APP_NAME=MyChart Patient Mobile App
        echo VITE_APP_VERSION=1.0.0
        echo VITE_NODE_ENV=development
        echo.
        echo # API Configuration ^(for future backend integration^)
        echo # VITE_API_BASE_URL=http://localhost:3001/api
        echo # VITE_API_TIMEOUT=10000
        echo.
        echo # Feature Flags
        echo VITE_ENABLE_MOBILE_MONEY=true
        echo VITE_ENABLE_BIOMETRIC_AUTH=true
        echo VITE_ENABLE_NOTIFICATIONS=true
        echo.
        echo # Development Settings
        echo VITE_DEBUG_MODE=true
        echo VITE_SHOW_CONSOLE_LOGS=true
    ) > .env.local
    echo [SUCCESS] Environment file created: .env.local
) else (
    echo [WARNING] Environment file already exists: .env.local
)

REM Install dependencies
echo [INFO] Installing project dependencies...
if "%USE_PNPM%"=="true" (
    echo [INFO] Using pnpm for installation...
    pnpm install
) else (
    echo [INFO] Using npm for installation...
    npm install
)

if %errorlevel% neq 0 (
    echo [ERROR] Failed to install dependencies
    pause
    exit /b 1
) else (
    echo [SUCCESS] Dependencies installed successfully!
)

REM Verify installation
echo [INFO] Verifying installation...
if exist "node_modules" (
    echo [SUCCESS] node_modules directory exists
) else (
    echo [ERROR] node_modules directory not found
    pause
    exit /b 1
)

if exist "node_modules\react\package.json" (
    echo [SUCCESS] React is installed
) else (
    echo [ERROR] React not found in node_modules
    pause
    exit /b 1
)

if exist "node_modules\vite\package.json" (
    echo [SUCCESS] Vite is installed
) else (
    echo [ERROR] Vite not found in node_modules
    pause
    exit /b 1
)

echo [SUCCESS] Installation verification completed!

REM Display next steps
echo.
echo 🎉 Installation completed successfully!
echo =====================================
echo.
echo Next steps:
echo 1. Start the development server:
if "%USE_PNPM%"=="true" (
    echo    pnpm run dev
) else (
    echo    npm run dev
)
echo.
echo 2. Open your browser and navigate to:
echo    http://localhost:5173
echo.
echo 3. Test the application using the LOCAL_TESTING_GUIDE.md
echo.
echo Available commands:
if "%USE_PNPM%"=="true" (
    echo    pnpm run dev      - Start development server
    echo    pnpm run build    - Build for production
    echo    pnpm run preview  - Preview production build
    echo    pnpm run lint     - Run ESLint
) else (
    echo    npm run dev       - Start development server
    echo    npm run build     - Build for production
    echo    npm run preview   - Preview production build
    echo    npm run lint      - Run ESLint
)
echo.
echo 📚 Documentation:
echo    - README.md - Complete project documentation
echo    - LOCAL_TESTING_GUIDE.md - Comprehensive testing guide
echo.
echo 🚀 Happy coding!
echo.
pause
