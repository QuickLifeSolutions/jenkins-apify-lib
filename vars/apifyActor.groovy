def call(Map cfg = [:]) {
  pipeline {
    agent any
    options { ansiColor('xterm'); timestamps(); durabilityHint('PERFORMANCE_OPTIMIZED'); skipDefaultCheckout(true) }
    environment {
      GIT_SSH_COMMAND = 'ssh -o StrictHostKeyChecking=no'
      NODE_IMAGE = cfg.get('nodeImage', 'node:20-alpine')
      DOCKER_ARGS = cfg.get('dockerArgs', '-v $HOME/.npm:/root/.npm')
      LOCK_RESOURCE = "apify/${env.JOB_NAME.replaceAll('/', '_')}"
      NPM_CONFIG_FUND = 'false'
      NPM_CONFIG_AUDIT = 'false'
    }
    triggers { pollSCM('') }
    stages {
      stage('Checkout') {
        steps {
          checkout([$class: 'GitSCM',
            branches: [[name: env.BRANCH_NAME ?: '*/main']],
            userRemoteConfigs: [[url: env.GIT_URL ?: scm.getUserRemoteConfigs()[0].getUrl(), credentialsId: 'github-token']],
            extensions: [[$class: 'CloneOption', depth: 0]]
          ])
        }
      }
      stage('Node Toolchain') { steps { script { docker.image(env.NODE_IMAGE).pull() } } }
      stage('Lint • Test • Build') {
        steps {
          script {
            docker.image(env.NODE_IMAGE).inside(env.DOCKER_ARGS) {
              sh '''
                set -e
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
              sh '''
                set -e
                if [ -f test/input.json ]; then npx --yes apify@^3 run -p test/input.json; else echo "no test/input.json"; fi
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
          withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
            sh '''
              set -e
              npx --yes standard-version@^9 --skip.tag=false --skip.commit=false --infile CHANGELOG.md
              git push origin HEAD:main
              git push origin --tags
            '''
          }
        }
      }
      stage('GitHub Release [tag]') {
        when { buildingTag() }
        steps {
          withCredentials([string(credentialsId: 'github-token', variable: 'GITHUB_TOKEN')]) {
            sh '''
              set -e
              TAG="${GIT_TAG_NAME:-$(git describe --tags --exact-match)}"
              REPO_API="$(git config --get remote.origin.url | sed -E 's#git@github.com:(.+)\.git#https://api.github.com/repos/\1/releases#')"
              BODY="$(awk 'BEGIN{on=0}/^## /{if(on){exit}on=1;next}on{print}' CHANGELOG.md || true)"
              curl -sS -H "Authorization: token $GITHUB_TOKEN" -H "Accept: application/vnd.github+json"                    -X POST -d "{\"tag_name\":\"${TAG}\",\"name\":\"${TAG}\",\"body\":\"${BODY//\"/\\\"}\"}" "$REPO_API" || true
            '''
          }
        }
      }
      stage('Deploy → Apify DEV') {
        when { anyOf { branch 'main'; buildingTag() } }
        steps {
          lock(resource: env.LOCK_RESOURCE, inversePrecedence: true) {
            withCredentials([string(credentialsId: 'apify-token-dev', variable: 'APIFY_TOKEN')]) {
              script { docker.image(env.NODE_IMAGE).inside(env.DOCKER_ARGS) { sh 'npx --yes apify@^3 push --force' } }
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
              script { docker.image(env.NODE_IMAGE).inside(env.DOCKER_ARGS) { sh 'npx --yes apify@^3 push --force' } }
            }
          }
        }
      }
    }
    post {
      success { archiveArtifacts artifacts: 'CHANGELOG.md', fingerprint: true; junit 'junit-report.xml' }
      always { cleanWs(deleteDirs: true, notFailBuild: true) }
    }
  }
}
