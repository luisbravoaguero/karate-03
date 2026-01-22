@svc_postmanEcho
Feature: Postman Echo - 10 easy smoke scenarios (for logging demo)

  Background:
    * url baseUrl
    * headers commonHeaders

  @houseDoor
  Scenario: Echo - POST /post echoes JSON body
    Given path 'post'
    And request { name: 'Luis', role: 'QA', active: true }
    When method post
    Then status 200
    * match response.json.name == 'Luis'
    * match response.json.role == 'QA'
    * match response.json.active == true

  @bedroom
  Scenario: Echo - POST /post echoes query params + JSON body
    Given path 'post'
    And param source = 'smoke'
    And request { item: 'book', qty: 2 }
    When method post
    Then status 200
    * match response.args.source == 'smoke'
    * match response.json.item == 'book'
    * match response.json.qty == 2

  @houseDoor
  Scenario: Echo - PUT /put echoes JSON body
    Given path 'put'
    And request { update: 'name', value: 'Sofi' }
    When method put
    Then status 200
    * match response.json.update == 'name'
    * match response.json.value == 'Sofi'

  @houseDoor
  Scenario: Echo - PATCH /patch echoes JSON body
    Given path 'patch'
    And request { patch: true, note: 'small change' }
    When method patch
    Then status 200
    * match response.json.patch == true
    * match response.json.note == 'small change'

  @colorGreen
  Scenario: Echo - DELETE /delete echoes JSON body
    Given path 'delete'
    And request { delete: true, id: 123 }
    When method delete
    Then status 200
    * match response.json.delete == true
    * match response.json.id == 123

  @colorGreen
  Scenario: Echo - HEAD /get returns 200
    Given path 'get'
    When method head
    Then status 200

  @baseYeto
  Scenario: Echo - GET /headers returns request headers in response JSON
    Given path 'headers'
    When method get
    Then status 200
    * match response.headers.accept contains 'application/json'

  @baseYeto
  Scenario: Echo - GET /response-headers returns custom response headers
    Given path 'response-headers'
    And param test = 'hello'
    When method get
    Then status 200
    * match responseHeaders['test'] == 'hello'
