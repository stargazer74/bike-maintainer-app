Feature: Creating an account in the app

  Background:
    Given the app runs in portal mode

  Scenario: Creating an account
    Given I open the app
    And I connect the app to my server
    When I click "Konto erstellen"
    And I fill in "E-Mail" with "new@example.org"
    And I fill in "Passwort" with "geheim-passwort-1"
    And I fill in "Passwort wiederholen" with "geheim-passwort-1"
    And I click "Konto erstellen"
    Then I see the text "Bitte bestätige deine E-Mail-Adresse"
    And I see the text "Wir haben eine E-Mail an new@example.org geschickt."
    And the backend received a registration for "new@example.org"

  Scenario: Sending the confirmation e-mail again
    Given I open the app
    And I connect the app to my server
    When I click "Konto erstellen"
    And I fill in "E-Mail" with "new@example.org"
    And I fill in "Passwort" with "geheim-passwort-1"
    And I fill in "Passwort wiederholen" with "geheim-passwort-1"
    And I click "Konto erstellen"
    And I click "Erneut senden"
    Then I see the text "Die E-Mail wurde erneut verschickt."
    And the backend sent the confirmation e-mail again to "new@example.org"

  @wip
  Scenario: Back to the login after registering
    Given I open the app
    And I connect the app to my server
    When I click "Konto erstellen"
    And I fill in "E-Mail" with "new@example.org"
    And I fill in "Passwort" with "geheim-passwort-1"
    And I fill in "Passwort wiederholen" with "geheim-passwort-1"
    And I click "Konto erstellen"
    And I click "Zur Anmeldung"
    Then I see a button "Anmelden"

  Scenario: The passwords do not match
    Given I open the app
    And I connect the app to my server
    When I click "Konto erstellen"
    And I fill in "E-Mail" with "new@example.org"
    And I fill in "Passwort" with "geheim-passwort-1"
    And I fill in "Passwort wiederholen" with "geheim-passwort-2"
    And I click "Konto erstellen"
    Then I see the text "Die Passwörter stimmen nicht überein."
    And the backend received no registration

  Scenario: The password is too short
    Given I open the app
    And I connect the app to my server
    When I click "Konto erstellen"
    And I fill in "E-Mail" with "new@example.org"
    And I fill in "Passwort" with "kurz"
    And I fill in "Passwort wiederholen" with "kurz"
    And I click "Konto erstellen"
    Then I see the text "Das Passwort muss 10 bis 72 Zeichen haben."
    And the backend received no registration
