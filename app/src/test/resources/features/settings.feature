Feature: Settings and account

  Background:
    Given I am logged in as "rider@example.org"

  Scenario: Logging out
    Given I open the app
    When I click "Einstellungen"
    And I click "Abmelden"
    Then I see the text "Möchtest du dich auf diesem Gerät abmelden?"
    When I click "Abmelden"
    Then I see a button "Anmelden"
    And I am logged out on the backend

  Scenario: Switching to another server
    Given I open the app
    When I click "Einstellungen"
    And I click "Server wechseln"
    And I click "Server wechseln"
    Then I see the text "Wie nutzt du Bike Maintainer?"

  @wip
  Scenario: Deleting the account
    Given I open the app
    When I click "Einstellungen"
    And I click "Konto löschen"
    And I fill in "Passwort" with "geheim-passwort-1"
    And I click "Weiter"
    Then I see the text "Wirklich löschen?"
    When I click "Endgültig löschen"
    Then the account "rider@example.org" no longer exists

  @wip
  Scenario: Deleting the account with a wrong password
    Given I open the app
    When I click "Einstellungen"
    And I click "Konto löschen"
    And I fill in "Passwort" with "falsch"
    And I click "Weiter"
    And I click "Endgültig löschen"
    Then I see the text "Das Passwort ist falsch."
    And the account "rider@example.org" still exists
