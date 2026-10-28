#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${JAVA_HOME:?Set JAVA_HOME to JDK 21}"
"$JAVA_HOME/bin/java" -version 2>&1 | head -1 | grep -Eq 'version "21\.' || { echo 'JDK 21 required' >&2; exit 1; }
# Each database test creates and drops only its own randomly named schema.
for service in IAM ACCESS BILLING PAYMENT ROUTING STORAGE OPERATIONS PROMOTION MEDIA; do
  export "${service}_TEST_MYSQL_URL=jdbc:mysql://127.0.0.1:13316/"
  export "${service}_TEST_MYSQL_USERNAME=root" "${service}_TEST_MYSQL_PASSWORD="
done
export IAM_BOOTSTRAP_ADMIN_USERNAME=release-test-admin
export IAM_BOOTSTRAP_ADMIN_PASSWORD=release-test-only-password
export IAM_ADMIN_LOGIN_TOKEN=release-test-only-admin-token-000000000000
export IAM_OTP_HMAC_KEY=release-test-only-otp-hmac-key-000000000000
export IAM_USER_INTERNAL_TOKEN=release-test-only-user-token-000000000000
modules=()
for service in iam user access billing payment routing storage media operations promotion; do
  modules+=("services/$service-service/$service-bootstrap")
done
projects=$(IFS=,; echo "${modules[*]}")
tests='ReleaseAuthMysqlTest,OtpPolicyMysqlTest,UserIdentityMysqlTest,AccessPersistenceMysqlTest,BillingLedgerMysqlTest,BillingMediaConcurrencyMysqlTest,HttpMediaKeyClientTest,BillingJdbcApplicationMysqlTest,PaymentWorkflowMysqlTest,PaymentHttpLoopMysqlTest,MyBatisRoutingRepositoryTest,MyBatisFileMetadataStoreTest,MyBatisOperationsStoreTest,OperationsApplicationAssemblyTest,MyBatisPromotionStoreTest,PromotionApplicationAssemblyTest,GenerationCatalogServiceTest,MediaTaskRulesTest,CreateAccessUrlHandlerTest,StorageControllerTest,RoutingControllerTest,ProductionProviderTest,MediaProviderConfigurationTest,MediaWorkflowTest,MyBatisMediaTaskStoreTest,MediaRestartTest,MediaApiTest,MediaHttpClientsTest,HttpBillingPortTest,MediaServiceLoopMysqlTest'
exec ./mvnw "$@" -pl "$projects" -am -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false package
