@catalogue @books @QuarterMaster

Feature: StorageAbstraction
As an API consumer
I want to be able to store a resource against a mapped URL
So that I can forget about the real location of the resource

Scenario: Storing a resource (full)
Given the QuarterMaster is up
And the all the storage destinations are working
And there is a binary resource to be stored with a resource id of <id>
When I request that QuarterMaster Stores a Resource
Then the resource is stored by QuarterMaster synchronously
And the response Json has the following attributes:
| attribute | type   | description                                                                                                          |
| url       | String | some mapped url that can be transformed by the mapping file so that it can retrieve a resource from the service      |


Scenario: Storing a resource (partial)
Given the QuarterMaster is up
And only some of all the storage destinations are working
And there is a binary resource to be stored with a resource id of <id>
When I request that QuarterMaster Stores a Resource
Then the resource is stored by QuarterMaster synchronously
And the response Json has the following attributes only for all of the resources that are up:
| attribute | type   | description                                                                                                          |
| url       | String | some mapped url that can be transformed by the mapping file so that it can retrieve a resource from the service      |



Scenario: Failed to store a Resource
Given the QuarterMaster is up
And none of the storage resources are working
And there is a binary resource to be stored with a resource id of <id>
When I request that QuarterMaster Stores a Resource
Then the client is given a status error

    
@smoke
Scenario: Trying to get a book with a nonexistent resource
When I request a resource that doesnt exist
Then the request fails because the resource was not found
