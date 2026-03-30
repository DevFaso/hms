#!/bin/bash
set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}▸ $1${NC}"; }
pass()  { echo -e "${GREEN}✔ $1${NC}"; }
fail()  { echo -e "${RED}✖ $1${NC}"; exit 1; }

cd "$(git rev-parse --show-toplevel)"

# ────── Frontend ──────
info "Running frontend checks..."
cd hospital-portal

info "Installing dependencies..."
npm ci --ignore-scripts
pass "Dependencies installed"

info "Checking formatting..."
npm run format:check || fail "Formatting check failed — run 'npm run format' to fix"
pass "Formatting OK"

info "Running linter..."
npm run lint || fail "Lint failed"
pass "Lint OK"

info "Building frontend..."
npm run build || fail "Build failed"
pass "Build OK"

info "Running unit tests..."
npm run test -- --watch=false || fail "Unit tests failed"
pass "Unit tests OK"

cd ..

# ────── Backend ──────
info "Running backend checks..."

chmod +x gradlew

info "Building & testing..."
./gradlew clean build --stacktrace || fail "Backend build/tests failed"
pass "Build & tests OK"

info "Verifying test coverage..."
./gradlew jacocoTestCoverageVerification || fail "Coverage below threshold"
pass "Coverage OK"

echo ""
echo -e "${GREEN}══════════════════════════════════════${NC}"
echo -e "${GREEN}  All quality checks passed!${NC}"
echo -e "${GREEN}══════════════════════════════════════${NC}"
