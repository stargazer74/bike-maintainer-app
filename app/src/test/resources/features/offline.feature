Feature: Using the app without a connection

  Background:
    Given I am logged in as "rider@example.org"
    And a motorcycle "Africa Twin" with 12345 km exists
    And the vehicle "Africa Twin" has the task "Ölwechsel" every 6000 km

  Scenario: Recording a maintenance without a connection and sending it later
    Given I open the app
    And I open the vehicle "Africa Twin"
    And I have no connection
    When I click "Wartung erfassen"
    And I fill in "Kilometerstand (km)" with "12500"
    And I click "Speichern"
    Then I see the text "Wartung auf dem Gerät gespeichert"
    And I see the text "Noch nicht übertragen"
    And the backend stored no maintenance log
    When the connection is back
    And the app sends what it kept on the device
    Then the backend stored a maintenance log for "Africa Twin" at 12500 km with the task "Ölwechsel"

  @wip
  Scenario: Entering the mileage without a connection
    Given I open the app
    And I open the vehicle "Africa Twin"
    And I have no connection
    When I click "Kilometerstand eintragen"
    And I fill in "Kilometerstand (km)" with "13000"
    And I click "Speichern"
    Then I see the text "Kilometerstand auf dem Gerät gespeichert"
    When the connection is back
    And the app sends what it kept on the device
    Then the backend has the vehicle "Africa Twin" with 13000 km

  @wip
  Scenario: Discarding what was kept on the device
    Given I open the app
    And I open the vehicle "Africa Twin"
    And I have no connection
    When I click "Wartung erfassen"
    And I click "Speichern"
    And I click "Verwerfen"
    Then I do not see the text "Noch nicht übertragen"
    When the connection is back
    And the app sends what it kept on the device
    Then the backend stored no maintenance log

  Scenario: Measurements without a connection after the vehicle was opened online
    Given the vehicle "Africa Twin" offers the worksheets "Bremsen"
    And I open the app
    And I open the vehicle "Africa Twin"
    And I have no connection
    When I click "Wartung erfassen"
    Then I see a button "Bremsen hinzufügen"
    And I see a button "Arbeitsblatt fotografieren"
