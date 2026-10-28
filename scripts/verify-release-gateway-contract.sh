#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -Eq 'version "21\.' || { echo 'JDK 21 required' >&2; exit 1; }
: "${IAM_TEST_MYSQL_URL:?Set the URL of an isolated local test MySQL instance}"
: "${IAM_BOOTSTRAP_ADMIN_USERNAME:?Set a test-only administrator username}"
: "${IAM_BOOTSTRAP_ADMIN_PASSWORD:?Set a test-only administrator password}"
: "${IAM_OTP_HMAC_KEY:?Set a test-only OTP key of at least 32 characters}"
: "${IAM_USER_INTERNAL_TOKEN:?Set a test-only IAM/User token of at least 32 characters}"
# Only Gateway, new-contract, browser-session and affected service assembly tests. Never regenerate during verification.
./mvnw "$@" -pl gateways/gateway-service,services/storage-service/storage-bootstrap,services/promotion-service/promotion-bootstrap,services/operations-service/operations-bootstrap -am \
  -Dtest=ReleaseContractTest,GatewayRouteTest,GatewayLocalRouteTest,GatewayHttpTest,GatewayUnavailableTest,BrowserIamSessionFilterTest,WebErrorAutoConfigurationTest,WebErrorAutoConfigurationIntegrationTest,PromotionApplicationAssemblyTest,OperationsApplicationAssemblyTest,com.xzwl.cloud.storage.TemplateApplicationTest \
  -Dsurefire.failIfNoSpecifiedTests=false package
# A separate JVM runs the actual Gateway JAR so servlet-service tests never load Gateway auto-configuration.
exec ./mvnw "$@" -pl services/iam-service/iam-bootstrap -am -Dtest=GatewaySessionMysqlTest \
  "-Dgateway.test.jar=$PWD/gateways/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar" \
  -Dsurefire.failIfNoSpecifiedTests=false test
