@smoke @svc_postmanEcho
Feature: Protected call (training)
  Background:
    * url baseUrl
    * headers commonHeaders
  Scenario: Use token from login in a second call
    * def loginResult = call read('classpath:features/calls/auth/login.feature')
    * def token = loginResult.toke
    * header Authorization = token
    #* configure headers = karate.merge(commonHeaders, { Authorization: token })

    Given path 'get'
    And param hello = 'world'
    When method get
    Then status 200

    * match response.headers.authorization == token
