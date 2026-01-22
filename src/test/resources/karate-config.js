function fn() {
  var env = karate.env || 'dev';
  var config = read('classpath:config/' + env + '.json');
  config.env = env;

  config.commonHeaders = {
    Accept: 'application/json',
    'Content-Type': 'application/json'
  };

  // ---- Multi-baseUrl support ----
  var service = karate.properties['service'] || 'dummyjson';
  config.service = service;

  if (!config.services || !config.services[service] || !config.services[service].baseUrl) {
    karate.log('Available services:', config.services);
    throw 'Missing baseUrl for service="' + service + '" in config/' + env + '.json';
  }

  // Default baseUrl for features that use "* url baseUrl"
  config.baseUrl = config.services[service].baseUrl;

  return config;
}
