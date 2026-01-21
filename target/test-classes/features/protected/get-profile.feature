Feature: Protected call (training)

  Scenario: Use token from login in a second call
    * url baseUrl
    * configure headers = commonHeaders

    # 1) get token from login.feature
    * def loginResult = call read('classpath:features/auth/login.feature')
    * def token = loginResult.toke
    * print token

    # 2) call another endpoint using the token
    * configure headers = karate.merge(commonHeaders, { Authorization: token })

    Given path 'get'
    And param hello = 'world'
    When method get
    Then status 200

    # Confirm header was sent (Postman Echo returns headers)
    * match response.headers.authorization == token
