Feature: Maintenance tasks of a vehicle

  Background:
    Given I am logged in as "rider@example.org"
    And a motorcycle "Africa Twin" with 12345 km exists

  Scenario: Without tasks the vehicle says so
    Given I open the app
    When I open the vehicle "Africa Twin"
    Then I see the text "Noch keine Wartungsaufgaben angelegt."

  Scenario: Creating a task
    Given I open the app
    When I open the vehicle "Africa Twin"
    And I click "Aufgabe hinzufügen"
    And I fill in "Name" with "Kette schmieren"
    And I fill in "Intervall in km" with "1000"
    And I click "Speichern"
    Then I see the text "Kette schmieren"
    And the backend has the task "Kette schmieren" every 1000 km for "Africa Twin"

  Scenario: A task needs an interval
    Given I open the app
    When I open the vehicle "Africa Twin"
    And I click "Aufgabe hinzufügen"
    And I fill in "Name" with "Kette schmieren"
    And I click "Speichern"
    Then I see the text "Bitte ein Intervall in km und/oder Monaten eingeben."

  Scenario: The status of a task by kilometres
    Given the vehicle "Africa Twin" has the task "Ölwechsel" every 6000 km, last done at 6000 km
    And the vehicle "Africa Twin" has the task "Kette spannen" every 1000 km, last done at 12000 km
    And the vehicle "Africa Twin" has the task "Ventile prüfen" every 24000 km
    And I open the app
    When I open the vehicle "Africa Twin"
    Then I see the text "Überfällig um 345 km"
    And I see the text "Erstfällig in 11.655 km"
    And I see the text "OK (655 km verbleibend)"

  Scenario: Changing the interval of a task
    Given the vehicle "Africa Twin" has the task "Ölwechsel" every 6000 km
    And I open the app
    When I open the vehicle "Africa Twin"
    And I click "Ölwechsel"
    And I fill in "Intervall in km" with "10000"
    And I click "Speichern"
    Then the backend has the task "Ölwechsel" every 10000 km for "Africa Twin"

  Scenario: Switching a task off
    Given the vehicle "Africa Twin" has the task "Ölwechsel" every 6000 km
    And I open the app
    When I open the vehicle "Africa Twin"
    And I click "Ölwechsel"
    And I check "Aktiv"
    And I click "Speichern"
    Then I see the text "Ausgeschaltete Aufgaben"
    And the task "Ölwechsel" is switched off on the backend
