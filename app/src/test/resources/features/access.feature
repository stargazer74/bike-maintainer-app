Feature: Trial and expired access

  Scenario: With expired access the data stays readable but nothing can be recorded
    Given I am logged in as "rider@example.org"
    And the access of the account "rider@example.org" has expired
    And a motorcycle "Africa Twin" with 12345 km exists
    And the vehicle "Africa Twin" has the task "Ölwechsel" every 6000 km
    And I open the app
    When I open the vehicle "Africa Twin"
    Then I see the text "Ölwechsel"
    And I see the text "Testphase abgelaufen – deine Daten bleiben erhalten, Erfassen ist nicht mehr möglich."
    And I see a button "Fahrzeugdaten anzeigen"
    And I do not see a button "Wartung erfassen"
    And I do not see a button "Kilometerstand eintragen"

  Scenario: Worksheets of the trial cannot be photographed
    Given I am logged in as "rider@example.org"
    And the account "rider@example.org" is in the trial
    And a motorcycle "Africa Twin" with 12345 km exists
    And the vehicle "Africa Twin" has the task "Bremsen prüfen" every 6000 km
    And the vehicle "Africa Twin" offers the worksheets "Bremsen"
    And I open the app
    When I open the vehicle "Africa Twin"
    And I click "Wartung erfassen"
    Then I see a button "Bremsen hinzufügen"
    And I see the text "Arbeitsblätter aus der Testphase können nicht eingescannt werden."
    And I do not see a button "Arbeitsblatt fotografieren"
