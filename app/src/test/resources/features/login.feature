Feature: Setting up the app and logging in

  Scenario: The online account leads to the login of the portal
    Given I open the app
    When I click "Online-Konto"
    Then I see a button "Anmelden"
    And I see a button "Konto erstellen"

  Scenario: Logging in on my own server
    Given the app runs in portal mode
    And an account "rider@example.org" exists
    And a motorcycle "Africa Twin" with 12345 km exists
    And I open the app
    When I click "Eigener Server"
    And I enter the address of my server
    And I click "Weiter"
    Then I see the text "Die Verbindung ist unverschlüsselt."
    When I click "Trotzdem verbinden"
    And I fill in "E-Mail" with "rider@example.org"
    And I fill in "Passwort" with "geheim-passwort-1"
    And I click "Anmelden"
    Then I see the text "Bike Maintainer erinnert dich an fällige Wartungen."
    When I click "Später"
    Then I see the text "Africa Twin"
    And I see the text "12.345 km"

  Scenario: Wrong password
    Given the app runs in portal mode
    And an account "rider@example.org" exists
    And I open the app
    When I connect the app to my server
    And I fill in "E-Mail" with "rider@example.org"
    And I fill in "Passwort" with "falsch"
    And I click "Anmelden"
    Then I see the text "E-Mail oder Passwort falsch."

  Scenario: Logging in before the e-mail address is confirmed
    Given the app runs in portal mode
    And an unverified account "new@example.org" exists
    And I open the app
    When I connect the app to my server
    And I fill in "E-Mail" with "new@example.org"
    And I fill in "Passwort" with "geheim-passwort-1"
    And I click "Anmelden"
    Then I see the text "Deine E-Mail-Adresse ist noch nicht bestätigt."

  Scenario: Logging in without e-mail and password
    Given the app runs in portal mode
    And I open the app
    When I connect the app to my server
    And I click "Anmelden"
    Then I see the text "Bitte E-Mail und Passwort eingeben."

  Scenario: An invalid server address
    Given I open the app
    When I click "Eigener Server"
    And I fill in "Server-Adresse" with "nicht eine adresse"
    And I click "Weiter"
    Then I see the text "Bitte eine gültige Adresse eingeben."

  Scenario: Already logged in, the app opens with the vehicles
    Given I am logged in as "rider@example.org"
    And a motorcycle "Africa Twin" with 12345 km exists
    When I open the app
    Then I see the text "Africa Twin"
