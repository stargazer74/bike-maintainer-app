Feature: Managing vehicles

  Background:
    Given I am logged in as "rider@example.org"

  Scenario: Without vehicles the list is empty
    Given I open the app
    Then I see the text "Noch keine Fahrzeuge erfasst."

  Scenario: The list shows each vehicle with its mileage and status
    Given a motorcycle "Africa Twin" with 12345 km exists
    And the vehicle "Africa Twin" has the task "Ölwechsel" every 10000 km
    And a car "Golf" with 80000 km exists
    When I open the app
    Then I see the text "Africa Twin"
    And I see the text "12.345 km"
    And I see the text "Überfällig"
    And I see the text "Golf"
    And I see the text "Keine Wartungsaufgaben"

  Scenario: Adding the first vehicle
    Given I open the app
    When I click "Erstes Fahrzeug anlegen"
    And I fill in "Name" with "Golf"
    And I click "Auto"
    And I fill in "Baujahr" with "2021"
    And I fill in "Kilometerstand (km)" with "5000"
    And I click "Speichern"
    Then I see the text "Golf"
    And the backend has a car "Golf"

  Scenario: A vehicle needs a name
    Given I open the app
    When I click "Erstes Fahrzeug anlegen"
    And I fill in "Baujahr" with "2021"
    And I click "Speichern"
    Then I see the text "Bitte einen Namen eingeben (bis 255 Zeichen)."
    And the backend has no vehicle ""

  Scenario: Renaming a vehicle
    Given a motorcycle "Africa Twin" with 12345 km exists
    And I open the app
    When I open the vehicle "Africa Twin"
    And I click "Fahrzeug bearbeiten"
    And I fill in "Name" with "Tenere"
    And I click "Speichern"
    Then I see the text "Tenere"
    And the backend has a motorcycle "Tenere"

  # Leaving after the delete is dropped while the page is not resumed yet (app#90).
  @bug @app-90
  Scenario: Deleting a vehicle
    Given a motorcycle "Africa Twin" with 12345 km exists
    And a car "Golf" with 80000 km exists
    And I open the app
    When I open the vehicle "Golf"
    And I click "Fahrzeug bearbeiten"
    And I click "Fahrzeug löschen"
    Then I see the text "Fahrzeug löschen?"
    When I click "Löschen"
    Then I see the text "Africa Twin"
    And I do not see the text "Golf"
    And the backend has no vehicle "Golf"

  @wip
  Scenario: Entering the current mileage
    Given a motorcycle "Africa Twin" with 12345 km exists
    And I open the app
    When I open the vehicle "Africa Twin"
    And I click "Kilometerstand eintragen"
    And I fill in "Kilometerstand (km)" with "13000"
    And I click "Speichern"
    Then I see the text "13.000 km"
    And the backend has the vehicle "Africa Twin" with 13000 km

  @wip
  Scenario: The mileage cannot go down
    Given a motorcycle "Africa Twin" with 12345 km exists
    And I open the app
    When I open the vehicle "Africa Twin"
    And I click "Kilometerstand eintragen"
    And I fill in "Kilometerstand (km)" with "12000"
    And I click "Speichern"
    Then I see the text "Darf nicht kleiner sein als bisher (12.345 km)."
    And the backend has the vehicle "Africa Twin" with 12345 km
