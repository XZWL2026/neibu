const fs = require('node:fs');
const path = require('node:path');
const YAML = require('yaml');
const root = path.join(__dirname, 'target');
const source = JSON.parse(fs.readFileSync(path.join(root, '.env.production-124.json'), 'utf8'));
const ports = {iam:8210,user:8211,access:8212,promotion:8213,billing:8214,payment:8215,routing:8216,media:8217,canvas:8218,storage:8219,operations:8220};
function localize(value) {
  if (Array.isArray(value)) return value.map(localize);
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([k,v]) => [k,localize(v)]));
  if (typeof value === 'string') {
    for (const [name,port] of Object.entries(ports)) {
      value = value.replaceAll(`http://172.31.218.43:${port}`,`http://127.0.0.1:${port}`)
        .replaceAll(`http://172.31.218.44:${port}`,`http://127.0.0.1:${port}`);
    }
  }
  return value;
}
const configs = source.nacos.map(config => {
  const doc = localize(YAML.parse(config.content));
  function flatten(value, prefix='', output={}) {
    for (const [key,child] of Object.entries(value)) {
      const name = prefix ? `${prefix}.${key}` : key;
      if (child && typeof child === 'object' && !Array.isArray(child)) flatten(child,name,output);
      else output[name] = child;
    }
    return output;
  }
  const properties = flatten(doc);
  if (config.dataId === 'gateway-service.yaml') properties['gateway.cors.allowed-origins'] = 'http://127.0.0.1:18090,http://127.0.0.1:18092,http://127.0.0.1:18080,http://127.0.0.1:5173,http://127.0.0.1:5174,http://127.0.0.1:5176';
  if (config.dataId === 'iam-service.yaml') {
    properties['iam.local-otp.enabled'] = true;
    properties['iam.local-otp.code'] = '123456';
    properties['iam.sms.provider'] = 'disabled';
    properties['server.servlet.session.cookie.secure'] = false;
  }
  // A production callback cannot deliver to a loopback-only development machine.
  // Keep the original provider configuration in the private source snapshot.
  if (config.dataId === 'payment-service.yaml') {
    properties['payment.provider'] = 'disabled';
    properties['payment.worker-enabled'] = true;
  }
  return {...config,tenant:'xzwl-local',content:YAML.stringify(properties)};
});
fs.writeFileSync(path.join(root,'.env.nacos-local.json'),JSON.stringify(configs,null,2));
console.log(`Localized ${configs.length} production Nacos configurations into xzwl-local`);
