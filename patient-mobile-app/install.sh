#!/bin/bash

# MyChart Patient Mobile App - Installation Script
# This script sets up the development environment and installs all dependencies

echo "🏥 MyChart Patient Mobile App - Installation Script"
echo "=================================================="

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Function to print colored output
print_status() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if Node.js is installed
check_node() {
    print_status "Checking Node.js installation..."
    if command -v node &> /dev/null; then
        NODE_VERSION=$(node --version)
        print_success "Node.js is installed: $NODE_VERSION"
        
        # Check if version is 18 or higher
        NODE_MAJOR_VERSION=$(echo $NODE_VERSION | cut -d'.' -f1 | sed 's/v//')
        if [ "$NODE_MAJOR_VERSION" -ge 18 ]; then
            print_success "Node.js version is compatible (18+)"
        else
            print_error "Node.js version must be 18 or higher. Current: $NODE_VERSION"
            print_status "Please update Node.js from: https://nodejs.org/"
            exit 1
        fi
    else
        print_error "Node.js is not installed"
        print_status "Please install Node.js from: https://nodejs.org/"
        exit 1
    fi
}

# Check if npm is installed
check_npm() {
    print_status "Checking npm installation..."
    if command -v npm &> /dev/null; then
        NPM_VERSION=$(npm --version)
        print_success "npm is installed: $NPM_VERSION"
    else
        print_error "npm is not installed"
        exit 1
    fi
}

# Check if pnpm is available (optional but recommended)
check_pnpm() {
    print_status "Checking for pnpm (optional but recommended)..."
    if command -v pnpm &> /dev/null; then
        PNPM_VERSION=$(pnpm --version)
        print_success "pnpm is available: $PNPM_VERSION"
        USE_PNPM=true
    else
        print_warning "pnpm is not installed. Using npm instead."
        print_status "To install pnpm for faster builds: npm install -g pnpm"
        USE_PNPM=false
    fi
}

# Install dependencies
install_dependencies() {
    print_status "Installing project dependencies..."
    
    if [ "$USE_PNPM" = true ]; then
        print_status "Using pnpm for installation..."
        pnpm install
    else
        print_status "Using npm for installation..."
        npm install
    fi
    
    if [ $? -eq 0 ]; then
        print_success "Dependencies installed successfully!"
    else
        print_error "Failed to install dependencies"
        exit 1
    fi
}

# Create necessary directories
create_directories() {
    print_status "Creating necessary directories..."
    
    # Create screenshots directory for testing
    mkdir -p screenshots
    
    # Create logs directory
    mkdir -p logs
    
    print_success "Directories created successfully!"
}

# Set up environment
setup_environment() {
    print_status "Setting up development environment..."
    
    # Create .env.local file if it doesn't exist
    if [ ! -f .env.local ]; then
        cat > .env.local << EOF
# Development Environment Variables
VITE_APP_NAME=MyChart Patient Mobile App
VITE_APP_VERSION=1.0.0
VITE_NODE_ENV=development

# API Configuration (for future backend integration)
# VITE_API_BASE_URL=http://localhost:3001/api
# VITE_API_TIMEOUT=10000

# Feature Flags
VITE_ENABLE_MOBILE_MONEY=true
VITE_ENABLE_BIOMETRIC_AUTH=true
VITE_ENABLE_NOTIFICATIONS=true

# Development Settings
VITE_DEBUG_MODE=true
VITE_SHOW_CONSOLE_LOGS=true
EOF
        print_success "Environment file created: .env.local"
    else
        print_warning "Environment file already exists: .env.local"
    fi
}

# Verify installation
verify_installation() {
    print_status "Verifying installation..."
    
    # Check if node_modules exists
    if [ -d "node_modules" ]; then
        print_success "node_modules directory exists"
    else
        print_error "node_modules directory not found"
        return 1
    fi
    
    # Check if key dependencies are installed
    if [ -f "node_modules/react/package.json" ]; then
        print_success "React is installed"
    else
        print_error "React not found in node_modules"
        return 1
    fi
    
    if [ -f "node_modules/vite/package.json" ]; then
        print_success "Vite is installed"
    else
        print_error "Vite not found in node_modules"
        return 1
    fi
    
    print_success "Installation verification completed!"
}

# Display next steps
show_next_steps() {
    echo ""
    echo "🎉 Installation completed successfully!"
    echo "====================================="
    echo ""
    echo "Next steps:"
    echo "1. Start the development server:"
    if [ "$USE_PNPM" = true ]; then
        echo "   ${GREEN}pnpm run dev${NC}"
    else
        echo "   ${GREEN}npm run dev${NC}"
    fi
    echo ""
    echo "2. Open your browser and navigate to:"
    echo "   ${BLUE}http://localhost:5173${NC}"
    echo ""
    echo "3. Test the application using the LOCAL_TESTING_GUIDE.md"
    echo ""
    echo "Available commands:"
    if [ "$USE_PNPM" = true ]; then
        echo "   ${YELLOW}pnpm run dev${NC}      - Start development server"
        echo "   ${YELLOW}pnpm run build${NC}    - Build for production"
        echo "   ${YELLOW}pnpm run preview${NC}  - Preview production build"
        echo "   ${YELLOW}pnpm run lint${NC}     - Run ESLint"
    else
        echo "   ${YELLOW}npm run dev${NC}       - Start development server"
        echo "   ${YELLOW}npm run build${NC}     - Build for production"
        echo "   ${YELLOW}npm run preview${NC}   - Preview production build"
        echo "   ${YELLOW}npm run lint${NC}      - Run ESLint"
    fi
    echo ""
    echo "📚 Documentation:"
    echo "   - README.md - Complete project documentation"
    echo "   - LOCAL_TESTING_GUIDE.md - Comprehensive testing guide"
    echo ""
    echo "🚀 Happy coding!"
}

# Main installation process
main() {
    echo ""
    print_status "Starting installation process..."
    echo ""
    
    # Run all checks and setup
    check_node
    check_npm
    check_pnpm
    create_directories
    setup_environment
    install_dependencies
    verify_installation
    
    # Show completion message
    show_next_steps
}

# Run the main function
main

# Make the script executable
chmod +x install.sh
