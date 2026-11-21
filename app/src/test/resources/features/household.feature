Feature: Household mode – choosing a person without login

  Scenario: Choosing my person
    Given the household has the profiles:
      | Anna |
      | Ben  |
    And I open the app
    When I connect the app to my server
    Then I see the text "Wer bist du?"
    When I click "Anna"
    And I click "Später"
    Then I see the text "Noch keine Fahrzeuge erfasst."

  Scenario: Creating the first person
    Given the app runs in household mode
    And I open the app
    When I connect the app to my server
    Then I see the text "Erste Person anlegen"
    When I fill in "Name" with "Anna"
    And I click "Hinzufügen"
    Then I see the text "Anna"
    And the backend has the profile "Anna"

  Scenario: A name that is already taken
    Given the household has the profiles:
      | Anna |
    And I open the app
    When I connect the app to my server
    And I click "Neue Person"
    And I fill in "Name" with "Anna"
    And I click "Hinzufügen"
    Then I see the text "Diesen Namen gibt es schon."

  Scenario: The chosen person opens with the vehicles
    Given I use the household profile "Anna"
    And a motorcycle "Africa Twin" with 12345 km exists
    When I open the app
    Then I see the text "Africa Twin"
