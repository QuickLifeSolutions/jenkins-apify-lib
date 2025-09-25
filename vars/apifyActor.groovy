def call(Map cfg = [:]) {
  pipeline {
    agent any
    options {
      ansiColor('xterm')
      timestamps()
      durabilityHint('PERFORMANCE_OPTIMIZED')
      skipDefaultCheckout(true)
      timeout(time: cfg.get('timeoutMinutes', 60) as int, unit: 'MINUTES')
    }
    environment {
      GIT_SSH_COMMAND = 'ssh -o StrictHostKeyChecking=no'
      NODE_IMAGE = cfg.get('nodeImage', 'node:20-bullseye')
      DOCKER_ARGS = cfg.get('dockerArgs', '-v $HOME/.npm:/root/.npm')
      LOCK_RESOURCE = "apify/${env.JOB_NAME.replaceAll('/', '_')}"
      NPM_CONFIG_FUND = 'false'
      NPM_CONFIG_AUDIT = 'false'
    }
    stages {
      stage('Checkout') {
        steps {
          checkout([$class: 'GitSCM',
            branches: [[name: env.BRANCH_NAME ?: '*/main']],
            userRemoteConfigs: [[url: env.GIT_URL ?: scm.getUserRemoteConfigs()[0].getUrl(), credentialsId: 'github-token']],
            extensions: [[$class: 'CloneOption', depth: 0]]
          ])
          sh "git config --global --add safe.directory '${WORKSPACE}'"
        }
      }
      stage('Node Toolchain') {
        steps {
          script { docker.image(env.NODE_IMAGE).pull() }
        }
      }
      stage('Lint • Test • Build') {
        steps {
          script {
            docker.image(env.NODE_IMAGE).inside(env.DOCKER_ARGS) {
              sh '''#!/bin/bash
                set -euo pipefail
                npm ci
                npm run lint || true
                npm test --if-present
                npm run build --if-present
              '''
            }
          }
        }
      }
      stage('Smoke (apify run)') {
        steps {
          script {
            docker.image(env.NODE_IMAGE).inside(env.DOCKER_ARGS) {
              sh '''#!/bin/bash
                set -euo pipefail
                if [ -f test/input.json ]; then
                  npx --yes apify@^3 run -p test/input.json
                else
                  echo 'no test/input.json'
                fi
              '''
            }
          }
        }
      }
      stage('Version & Tag [main]') {
        when { allOf { branch 'main'; not { buildingTag() } } }
        environment {
          GIT_AUTHOR_NAME = 'ci-bot'; GIT_AUTHOR_EMAIL = 'ci@local'
          GIT_COMMITTER_NAME = 'ci-bot'; GIT_COMMITTER_EMAIL = 'ci@local'
        }
        steps {
          withCredentials([usernamePassword(credentialsId: 'github-token', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD')]) {
            sh '''#!/bin/bash
              set -euo pipefail
              set +x
              ORIGINAL_REMOTE="$(git remote get-url origin)"
              REMOTE_PATH="${ORIGINAL_REMOTE#https://}"
              if [ "$REMOTE_PATH" = "$ORIGINAL_REMOTE" ]; then
                echo "Origin remote must use HTTPS to leverage PAT-based authentication" >&2
                exit 1
              fi
              trap "git remote set-url origin '$ORIGINAL_REMOTE'" EXIT
              git remote set-url origin "https://${GIT_USERNAME}:${GIT_PASSWORD}@${REMOTE_PATH}"
              npx --yes standard-version@^9 --skip.tag=false --skip.commit=false --infile CHANGELOG.md
              git push origin HEAD:main
              git push origin --tags
              git remote set-url origin "$ORIGINAL_REMOTE"
              set -x
            '''
          }
        }
      }
      stage('GitHub Release [tag]') {
        when { buildingTag() }
        steps {
          withCredentials([usernamePassword(credentialsId: 'github-token', usernameVariable: 'GIT_USERNAME', passwordVariable: 'GIT_PASSWORD')]) {
            withEnv(["GITHUB_TOKEN=${GIT_PASSWORD}"]) {
              sh '''#!/bin/bash
                set -euo pipefail
                set +x
                TAG="${GIT_TAG_NAME:-$(git describe --tags --exact-match)}"
                REPO_URL="$(git config --get remote.origin.url)"
                API_PATH="${REPO_URL#https://github.com/}"
                if [ "$API_PATH" = "$REPO_URL" ]; then
                  API_PATH="${REPO_URL#git@github.com:}"
                fi
                API_URL="https://api.github.com/repos/${API_PATH%.git}/releases"
                BODY="$(awk 'BEGIN{on=0}/^## /{if(on){exit}on=1;next}on{print}' CHANGELOG.md || true)"
                export TAG BODY
                PAYLOAD="$(node -e "console.log(JSON.stringify({tag_name: process.env.TAG, name: process.env.TAG, body: process.env.BODY || ''}))")"
                RESPONSE_FILE="$(mktemp)"
                HTTP_STATUS=$(curl -sS --fail-with-body \
                  -H "Authorization: token ${GITHUB_TOKEN}" \
                  -H "Accept: application/vnd.github+json" \
                  -o "$RESPONSE_FILE" \
                  -w '%{http_code}' \
                  -X POST \
                  --data-raw "$PAYLOAD" \
                  "$API_URL" || true)
                if [ "$HTTP_STATUS" != "201" ] && [ "$HTTP_STATUS" != "409" ]; then
                  cat "$RESPONSE_FILE" >&2
                  rm -f "$RESPONSE_FILE"
                  exit 1
                fi
                rm -f "$RESPONSE_FILE"
                set -x
              '''
            }
          }
        }
      }
      stage('Deploy → Apify DEV') {
        when { anyOf { branch 'main'; buildingTag() } }
        steps {
          lock(resource: env.LOCK_RESOURCE, inversePrecedence: true) {
            withCredentials([string(credentialsId: 'apify-token-dev', variable: 'APIFY_TOKEN')]) {
              script {
                docker.image(env.NODE_IMAGE).inside(env.DOCKER_ARGS) {
                  sh 'npx --yes apify@^3 push --force'
                }
              }
            }
          }
        }
      }
      stage('Promote → Apify PROD [manual gate]') {
        when { buildingTag() }
        steps {
          input message: "Promote ${env.GIT_TAG_NAME ?: 'current'} to PROD?"
          lock(resource: env.LOCK_RESOURCE, inversePrecedence: true) {
            withCredentials([string(credentialsId: 'apify-token-prod', variable: 'APIFY_TOKEN')]) {
              script {
                docker.image(env.NODE_IMAGE).inside(env.DOCKER_ARGS) {
                  sh 'npx --yes apify@^3 push --force'
                }
              }
            }
          }
        }
      }
    }
    post {
      success {
        archiveArtifacts artifacts: 'CHANGELOG.md', allowEmptyArchive: true, fingerprint: true
        junit testResults: 'junit-report.xml', allowEmptyResults: true
      }
      always {
        cleanWs(deleteDirs: true, notFailBuild: true)
      }
    }
  }
}
