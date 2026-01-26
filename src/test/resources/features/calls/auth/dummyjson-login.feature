Feature: DummyJSON Login helper (called)

  Scenario: Login and return Authorization header
    * url baseUrl
    * headers commonHeaders

    Given path 'auth', 'login'
    And request { username: '#(serviceConfig.username)', password: '#(serviceConfig.password)' }
    When method post
    * assert responseStatus == 200 || responseStatus == 201

    * def rawToken = response.accessToken ? response.accessToken : response.token
    * match rawToken == '#string'

    * def authHeader = 'Bearer ' + rawToken

