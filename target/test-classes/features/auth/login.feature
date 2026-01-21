Feature: Login (training)

  Scenario: Login returns a token-like value
    * url baseUrl
    * configure headers = commonHeaders

    Given path 'post'
    And request { username: 'demo', password: 'demo' }
    When method post
    Then status 200

    # Postman Echo returns what you sent under response.json
    * def toke = 'Bearer ' + response.json.username + '-token'
    * print toke
    * match toke contains 'Bearer'
