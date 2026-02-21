#!/usr/bin/env bash
# Test runner for lab-orchestrator
# Usage: ./test [unit|integration|e2e|all]

set -e
cd "$(dirname "$0")"

run_test() {
    echo ""
    echo "=== Running $1 tests ==="
    bb "test/${1}_test.clj"
}

case "${1:-unit}" in
    unit)
        run_test unit
        ;;
    integration)
        run_test integration
        ;;
    schedule)
        run_test schedule
        ;;
    e2e)
        run_test e2e
        ;;
    meal-prep)
        echo ""
        echo "=== Running meal-prep E2E test (starts real containers, ~90s) ==="
        bb "test/e2e_meal_prep_test.clj"
        ;;
    all)
        run_test unit
        run_test integration
        run_test schedule
        run_test e2e
        ;;
    *)
        echo "Usage: $0 [unit|integration|schedule|e2e|meal-prep|all]"
        exit 1
        ;;
esac

echo ""
echo "All tests passed!"
