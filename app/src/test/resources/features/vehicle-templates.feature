Feature: Maintenance plans from templates

  Background:
    Given I am logged in as "rider@example.org"
    And a template for the motorcycle "Honda" "CB 650 R" with the tasks "Ölwechsel, Kettenspannung" exists

  Scenario: A new vehicle with a matching template is offered the maintenance plan
    Given I open the app
    When I click "Erstes Fahrzeug anlegen"
    And I fill in "Name" with "Hornet"
    And I fill in "Hersteller" with "Honda"
    And I fill in "Modell" with "CB 650 R"
    And I fill in "Baujahr" with "2020"
    Then I see the text "Für dieses Modell gibt es einen Wartungsplan"
    When I click "Speichern"
    Then I see the text "Wartungsplan aus Vorlage"
    And I see the text "Die Vorlagen sind Richtwerte ohne Gewähr."
    And the button "Übernehmen" is disabled
    When I check "Ich habe den Hinweis gelesen."
    Then the button "Übernehmen" is enabled
    When I click "Übernehmen"
    Then I see the text "2 übernommen, 0 schon vorhanden"
    And I see the text "Kettenspannung"
    And the backend has the task "Ölwechsel" from the template for "Hornet"
    And the backend has the task "Kettenspannung" from the template for "Hornet"

  Scenario: A half filled form searches nothing and saving stays possible
    Given I open the app
    When I click "Erstes Fahrzeug anlegen"
    And I fill in "Name" with "Hornet"
    And I fill in "Hersteller" with "H"
    And I fill in "Baujahr" with "2020"
    Then I do not see the text "Für dieses Modell gibt es einen Wartungsplan"
    When I click "Speichern"
    Then the backend has a motorcycle "Hornet"
    And the backend received no template search

  Scenario: Applying a template to an existing vehicle fills in what was last done
    Given a motorcycle "Hornet" with 12345 km exists
    And the vehicle "Hornet" is a "Honda" "CB 650 R"
    And I open the app
    When I open the vehicle "Hornet"
    And I click "Vorlage übernehmen"
    Then I see the text "Ölwechsel"
    And I see the text "Aufgaben ohne Angabe erscheinen als überfällig."
    When I click "Leere Felder: aktueller Stand und heute"
    And I check "Ich habe den Hinweis gelesen."
    And I click "Übernehmen"
    Then I see the text "2 übernommen, 0 schon vorhanden"
    And the backend has the task "Ölwechsel" for "Hornet" last done at 12345 km

  Scenario: Tasks that came from the template are marked and cannot be applied again
    Given a motorcycle "Hornet" with 12345 km exists
    And the vehicle "Hornet" is a "Honda" "CB 650 R"
    And I open the app
    When I open the vehicle "Hornet"
    And I click "Vorlage übernehmen"
    And I check "Ich habe den Hinweis gelesen."
    And I click "Übernehmen"
    Then I see the text "2 übernommen, 0 schon vorhanden"
    When I click "Vorlage übernehmen"
    Then I see the text "schon vorhanden"
    When I check "Ich habe den Hinweis gelesen."
    Then the button "Übernehmen" is disabled

  Scenario: The confirmation starts over every time the template is opened
    Given a motorcycle "Hornet" with 12345 km exists
    And the vehicle "Hornet" is a "Honda" "CB 650 R"
    And I open the app
    When I open the vehicle "Hornet"
    And I click "Vorlage übernehmen"
    And I check "Ich habe den Hinweis gelesen."
    Then the button "Übernehmen" is enabled
    When I go back
    And I click "Vorlage übernehmen"
    Then the button "Übernehmen" is disabled

  Scenario: Searching for another make and model
    Given a motorcycle "Hornet" with 12345 km exists
    And I open the app
    When I open the vehicle "Hornet"
    And I click "Vorlage übernehmen"
    And I fill in "Hersteller" with "honda"
    And I fill in "Modell" with "cb650r"
    And I click "Suchen"
    Then I see the text "Ölwechsel"
    And the button "Übernehmen" is disabled

  Scenario: Several templates are offered to choose from
    Given a template for the motorcycle "Honda" "CB 650 F" with the tasks "Luftfilter" exists
    And a motorcycle "Hornet" with 12345 km exists
    And the vehicle "Hornet" is a "Honda" "CB 650"
    And I open the app
    When I open the vehicle "Hornet"
    And I click "Vorlage übernehmen"
    Then I see the text "Vorlage wählen"
    When I click "Honda CB 650 F"
    Then I see the text "Luftfilter"
    And I do not see the text "Ölwechsel"

  Scenario: Without a matching template nothing is offered
    Given a motorcycle "Hornet" with 12345 km exists
    And the vehicle "Hornet" is a "Yamaha" "MT-07"
    And I open the app
    When I open the vehicle "Hornet"
    And I click "Vorlage übernehmen"
    Then I see the text "Keine Vorlage gefunden"

  Scenario: Without a connection the template cannot be applied
    Given a motorcycle "Hornet" with 12345 km exists
    And the vehicle "Hornet" has the task "Reifen prüfen" every 6000 km
    And I open the app
    And I open the vehicle "Hornet"
    And I go back
    And I have no connection
    When I open the vehicle "Hornet"
    Then I see the text "Nur mit Verbindung möglich"
    And the button "Vorlage übernehmen" is disabled

  Scenario: Losing the connection while applying keeps nothing on the device
    Given a motorcycle "Hornet" with 12345 km exists
    And the vehicle "Hornet" is a "Honda" "CB 650 R"
    And I open the app
    When I open the vehicle "Hornet"
    And I click "Vorlage übernehmen"
    Then I see the text "Die Vorlagen sind Richtwerte ohne Gewähr."
    When I have no connection
    And I check "Ich habe den Hinweis gelesen."
    And I click "Übernehmen"
    Then I see the text "Keine Verbindung."
    And the backend has no task "Ölwechsel"

  Scenario: With expired access there is no template offer
    Given the access of the account "rider@example.org" has expired
    And a motorcycle "Hornet" with 12345 km exists
    And I open the app
    When I open the vehicle "Hornet"
    Then I see a button "Fahrzeugdaten anzeigen"
    And I do not see a button "Vorlage übernehmen"
