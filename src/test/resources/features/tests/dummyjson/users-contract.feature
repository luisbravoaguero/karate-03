@svc_dummyjson
@contract
Feature: DummyJSON Users - Contract

  Background:
    * url baseUrl
    * headers commonHeaders

  Scenario: Users - GET /users/1 returns a user with stable fields
    Given path 'users', 1
    When method get
    Then status 200
    * match response contains { id: 1, firstName: '#string', lastName: '#string', age: '#number' }

  Scenario: Users - GET /users/search returns users array + metadata
    Given path 'users', 'search'
    And param q = 'john'
    When method get
    Then status 200
    * match response contains { users: '#[]', total: '#number', skip: '#number', limit: '#number' }
