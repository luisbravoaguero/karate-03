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

	// Default baseUrl for features that use "* url baseUrl"
	//config.baseUrl = config.services[service].baseUrl;

	var svc = config.services[service];

	// Default baseUrl for features that use "* url baseUrl"
	config.baseUrl = svc.baseUrl;

	// Expose the full service block to features/helpers
	config.serviceConfig = svc;

	return config;
}