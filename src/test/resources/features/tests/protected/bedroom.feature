@svc_postmanEcho @somke
Feature: Postman Bedroom

  Background:
    * url baseUrl
    * headers commonHeaders

  @baseYeto
  Scenario: Bedroom - GET /get echoes query params
    Given path 'get'
    And param foo1 = 'bar1'
    And param foo2 = 'bar2'
    When method get
    Then status 200
    * match response.args.foo1 == 'bar1'
    * match response.args.foo2 == 'bar2'

  @baseYeto
  Scenario: Bedroom - GET /get echoes the full URL
    Given path 'get'
    And param hello = 'world'
    When method get
    Then status 200
    * match response.url contains 'hello=world'