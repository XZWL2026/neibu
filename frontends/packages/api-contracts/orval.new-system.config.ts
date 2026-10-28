import { defineConfig } from 'orval';
import { resolve } from 'node:path';

export default defineConfig({
  newSystem: {
    input: {
      target: resolve(process.cwd(), 'contracts/openapi/public/xzwl-new-system-1.0.0.json'),
      override: { transformer: spec => {
        // Orval drops null from an anyOf enum; use the equivalent nullable enum for these payment fields.
        for (const name of ['request_payment.interfaces.http.PaymentController_NewRecharge', 'response_payment.interfaces.http.PaymentController_View']) {
          const schema = spec.components?.schemas?.[name];
          if (schema && 'properties' in schema && schema.properties?.paymentMethod) {
            const field = schema.properties.paymentMethod;
            const members = 'anyOf' in field ? field.anyOf : undefined;
            const stringEnum = members?.find((item: Record<string, unknown>) => 'type' in item && item.type === 'string' && 'enum' in item);
            if (members?.length === 2 && stringEnum && 'enum' in stringEnum && stringEnum.enum && members.some((item: Record<string, unknown>) => 'type' in item && item.type === 'null')) {
              schema.properties.paymentMethod = { type: ['string', 'null'], enum: [...stringEnum.enum, null] };
            }
          }
        }
        return spec;
      } }
    },
    output: {
      target: resolve(process.env.ORVAL_OUTPUT_DIR ?? 'frontends/packages/api-contracts/src/generated-new-system', 'new-system.ts'),
      client: 'fetch',
      urlEncodeParameters: true,
      override: {
        mutator: { path: '@xzwl/api-contracts/gateway', name: 'gatewayRequest' },
        fetch: { includeHttpResponseReturnType: true, forceSuccessResponse: true }
      }
    }
  }
});
