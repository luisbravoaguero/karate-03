@svc_dummyjson
@contract
Feature: DummyJSON Products - Contract

  Background:
    * url baseUrl
    * headers commonHeaders


  Scenario: Products - GET /products/1 returns expected fields
    Given path 'products', 1
    When method get
    Then status 200
    * match response contains
      """
      {
        id: 1,
        title: '#string',
        price: '#number',
        category: '#string',
        thumbnail: '#string'
      }
      """

  Scenario: Products - GET /products returns list + metadata
    Given path 'products'
    And param limit = 5
    When method get
    Then status 200
    * match response contains { products: '#[]', total: '#number', skip: '#number', limit: '#number' }
    * match response.products.length == 5

  Scenario: Products - POST /products/add returns created product data
    Given path 'products', 'add'
    And request { title: 'Karate Product', price: 123, description: 'created by karate', category: 'beauty' }
    When method post
    * assert responseStatus == 200 || responseStatus == 201
    * match response contains { id: '#number', title: 'Karate Product', price: 123 }
