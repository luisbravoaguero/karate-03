function fn() {
  var env = karate.env || 'dev';
  var config = read('classpath:config/' + env + '.json');
  config.env = env;

  config.commonHeaders = {
    Accept: 'application/json',
    'Content-Type': 'application/json'
  };

  // ---- Fail-fast service selection ----
  var service = karate.properties['service'];

  // 1) Fail if missing
  if (!service || !service.trim()) {
    karate.fail('Missing -Dservice. Example: -Dservice=dummyjson (check config/' + env + '.json > services)');
  }
  service = service.trim();
  config.service = service;

  // 2) Fail if not found / baseUrl missing
  if (!config.services || !config.services[service] || !config.services[service].baseUrl) {
    var available = config.services ? Object.keys(config.services).join(', ') : '(none)';
    karate.fail('Unknown service="' + service + '". Available: ' + available + ' (check config/' + env + '.json > services)');
  }

  var svc = config.services[service];

  // Default baseUrl for features that use "* url baseUrl"
  config.baseUrl = svc.baseUrl;

  // Expose the full service block to features/helpers
  config.serviceConfig = svc;

  // -----------------------------------
  // ✅ Retry config (global defaults)
  // -----------------------------------
  var props = karate.properties;

  // Support both naming styles (so you don't break Jenkins/local commands):
  // -Dretries=2 OR -Dkarate.retries=2
  // -DretryInterval=1000 OR -Dkarate.retryInterval=1000
  var retriesRaw =
    props['retries'] || props['karate.retries'] || '0';

  var intervalRaw =
    props['retryInterval'] || props['karate.retryInterval'] || '1000';

  var retries = parseInt(retriesRaw, 10);
  var retryInterval = parseInt(intervalRaw, 10);

  if (isNaN(retries) || retries < 0) retries = 0;
  if (isNaN(retryInterval) || retryInterval < 0) retryInterval = 1000;

  karate.configure('retry', { count: retries, interval: retryInterval });

  // Optional log (only if user explicitly set any retry property)
  var userTouchedRetry =
    props['retries'] || props['karate.retries'] || props['retryInterval'] || props['karate.retryInterval'];

  if (userTouchedRetry) {
    karate.log('Retry config -> count:', retries, 'interval(ms):', retryInterval);
  }

  return config;
}
