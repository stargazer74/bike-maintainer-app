Feature: Measurements and worksheets when recording a maintenance

  Background:
    Given I am logged in as "rider@example.org"
    And a motorcycle "Africa Twin" with 12345 km exists
    And the vehicle "Africa Twin" has the task "Bremsen prüfen" every 6000 km

  Scenario: The worksheets of the vehicle are offered
    Given the vehicle "Africa Twin" offers the worksheets "Ventilspiel, Bremsen"
    And I open the app
    When I open the vehicle "Africa Twin"
    And I click "Wartung erfassen"
    Then I see a button "Ventilspiel hinzufügen"
    And I see a button "Bremsen hinzufügen"
    And I see a button "Arbeitsblatt fotografieren"

  Scenario: Without worksheets there is nothing to measure
    Given I open the app
    When I open the vehicle "Africa Twin"
    And I click "Wartung erfassen"
    Then I do not see a button "Bremsen hinzufügen"
    And I do not see a button "Arbeitsblatt fotografieren"

  Scenario: Recording the brake pads
    Given the vehicle "Africa Twin" offers the worksheets "Bremsen"
    And I open the app
    When I open the vehicle "Africa Twin"
    And I click "Wartung erfassen"
    And I click "Bremsen hinzufügen"
    And I fill in "Belag vorne" with "3,5"
    And I click "Speichern"
    Then I see the text "Wartung gespeichert."
    And the backend stored the brake pad front "3.5" mm for "Africa Twin"
    And I see the text "Letzte Messwerte"
    And I see the text "3,5 mm"

  Scenario: An invalid measured value
    Given the vehicle "Africa Twin" offers the worksheets "Bremsen"
    And I open the app
    When I open the vehicle "Africa Twin"
    And I click "Wartung erfassen"
    And I click "Bremsen hinzufügen"
    And I fill in "Belag vorne" with "3,5,5"
    And I click "Speichern"
    Then I see the text "Bitte korrigiere die markierten Messwerte."
    And the backend stored no maintenance log

  Scenario: Removing a measurement again
    Given the vehicle "Africa Twin" offers the worksheets "Bremsen"
    And I open the app
    When I open the vehicle "Africa Twin"
    And I click "Wartung erfassen"
    And I click "Bremsen hinzufügen"
    And I click "Bremsen entfernen"
    Then I see a button "Bremsen hinzufügen"
