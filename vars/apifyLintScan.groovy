def call() {
  sh '''
    set -e
    npx --yes trufflehog@^3 filesystem --no-update --only-verified --json . || true
    npm audit --omit=dev || true
  '''
}
