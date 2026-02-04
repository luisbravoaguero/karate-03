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

  // ----------------------------
  // 4) Retry configuration (recommended)
  //
  // Convention:
  //   -Dretries=1  => 1 attempt total (NO retry)
  //   -Dretries=2  => 2 attempts total (1 retry)
  //   -Dretries=3  => 3 attempts total (2 retries)
  //
  // NOTE: If you use "retry until ..." in a feature, retries MUST be >= 1,
  // otherwise Karate will fail with "too many retry attempts: 0".
  // ----------------------------
  var retryCountRaw = karate.properties['retries'];          // e.g. "1", "2", "3"
  var retryIntervalRaw = karate.properties['retryInterval']; // e.g. "1000"

  var retryCount = retryCountRaw ? parseInt(retryCountRaw, 10) : 1;       // default: 1 (no retry)
  var retryInterval = retryIntervalRaw ? parseInt(retryIntervalRaw, 10) : 1000;

  if (isNaN(retryCount) || retryCount < 1) {
    karate.fail("Invalid -Dretries=" + retryCountRaw + ". Use 1 for no retry, 2+ for retries.");
  }
  if (isNaN(retryInterval) || retryInterval < 0) {
    karate.fail("Invalid -DretryInterval=" + retryIntervalRaw + ". Use 0 or a positive number (ms).");
  }

  // Global retry settings (used when you write: "And retry until <condition>")
  karate.configure('retry', { count: retryCount, interval: retryInterval });

  // Optional: expose in config for debugging/logging if you want
  config.retry = { count: retryCount, interval: retryInterval };

  return config;
}
