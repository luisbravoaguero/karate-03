@svc_dummyjson @smoke
Feature: DummyJSON Workflow - Regression

  Background:
    * url baseUrl
    * headers commonHeaders

  Scenario: Workflow - login -> auth/me -> users/{id} -> posts/add
    * def login = call read('classpath:features/calls/auth/dummyjson-login.feature')
    * header Authorization = login.authHeader

    # 1) me (protected)
    Given path 'auth', 'me'
    When method get
    Then status 200
    * match response contains { id: '#number', username: '#string' }
    * def userId = response.id

    # 2) user details (public)
    Given path 'users', userId
    When method get
    Then status 200
    * match response.id == userId

    # 3) add post (public)
    Given path 'posts', 'add'
    And request { title: 'Post created by Karate', userId: '#(userId)' }
    When method post
    * assert responseStatus == 200 || responseStatus == 201
    * match response contains { id: '#number', title: '#string', userId: '#number' }
