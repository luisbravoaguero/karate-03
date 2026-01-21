function fn() {
  var env = karate.env || 'dev';
  var config = read('classpath:config/' + env + '.json');

  // expose env too (helps debugging)
  config.env = env;

  // common headers (no auth here yet)
  config.commonHeaders = {
    Accept: 'application/json',
    'Content-Type': 'application/json'
  };

  return config;
}
