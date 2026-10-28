import { billingAnalyticsGetApiV1AdminBillingConsumptionsAnalytics } from '../api';

/** Bound slow aggregation requests without changing their accounting scope. */
export async function consumptionAnalytics(query: Parameters<typeof billingAnalyticsGetApiV1AdminBillingConsumptionsAnalytics>[0]) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 60_000);
  try {
    return await billingAnalyticsGetApiV1AdminBillingConsumptionsAnalytics(query, { signal: controller.signal });
  } finally {
    clearTimeout(timeout);
  }
}
