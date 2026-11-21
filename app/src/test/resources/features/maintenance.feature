Feature: Recording a maintenance

  Background:
    Given I am logged in as "rider@example.org"
    And a motorcycle "Africa Twin" with 12345 km exists
    And the vehicle "Africa Twin" has the task "Ölwechsel" every 6000 km

  Scenario: Recording a maintenance with a performed task
    Given I open the app
    When I open the vehicle "Africa Twin"
    And I click "Wartung erfassen"
    And I fill in "Kilometerstand (km)" with "12500"
    And I click "Speichern"
    Then I see the text "Wartung gespeichert."
    And the backend stored a maintenance log for "Africa Twin" at 12500 km with the task "Ölwechsel"
    And the backend has the vehicle "Africa Twin" with 12500 km

  Scenario: Recording without raising the mileage of the vehicle
    Given I open the app
    When I open the vehicle "Africa Twin"
    And I click "Wartung erfassen"
    And I fill in "Kilometerstand (km)" with "12500"
    And I check "Kilometerstand des Fahrzeugs auf 12.500 km anheben"
    And I click "Speichern"
    Then I see the text "Wartung gespeichert."
    And the backend stored a maintenance log for "Africa Twin" at 12500 km with the task "Ölwechsel"
    And the backend has the vehicle "Africa Twin" with 12345 km

  Scenario: A maintenance needs a performed task
    Given I open the app
    When I open the vehicle "Africa Twin"
    And I click "Wartung erfassen"
    And I check "Ölwechsel"
    And I click "Speichern"
    Then I see the text "Bitte mindestens eine Aufgabe auswählen."
    And the backend stored no maintenance log

  Scenario: The mileage must be a number
    Given I open the app
    When I open the vehicle "Africa Twin"
    And I click "Wartung erfassen"
    And I clear "Kilometerstand (km)"
    And I click "Speichern"
    Then I see the text "Bitte eine ganze Zahl eingeben."
    And the backend stored no maintenance log
