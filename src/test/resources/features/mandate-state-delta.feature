@mrg
Feature: MRG mandate state-delta report per clock window

  MRG projects one mandate state-delta report per client per clock window from
  the mandate projection (written by MSR). Only mandates whose state moved past
  the client's watermark are reported (R-29); an unchanged window emits a
  zero-delta heartbeat (SYNTHETIC A-57) so the consumer can tell "no movement"
  from "MRG dead"; a resend re-reports every current mandate state.

  Background:
    Given a mandate-capable MRG client

  Scenario: First window reports every mandate state-delta ordered by mandate ref
    Given mandate "M001" has projection state "PDNG"
    And mandate "M002" has projection state "ACCP"
    When the MRG window "w1" runs
    Then the MRG report for window "w1" lists exactly:
      | mandate | state |
      | M001    | PDNG  |
      | M002    | ACCP  |
    And the client watermark holds exactly:
      | mandate | state |
      | M001    | PDNG  |
      | M002    | ACCP  |

  Scenario: An unchanged window emits a zero-delta heartbeat
    Given mandate "M001" has projection state "PDNG"
    And the MRG window "w1" has already run
    When the MRG window "w2" runs
    Then the MRG report for window "w2" is a zero-delta heartbeat
    And the client watermark is unchanged by the heartbeat

  Scenario: A single state flip reports exactly that mandate in the next window
    Given mandate "M001" has projection state "PDNG"
    And mandate "M002" has projection state "PDNG"
    And the MRG window "w1" has already run
    When mandate "M001" advances to state "ACCP"
    And the MRG window "w2" runs
    Then the MRG report for window "w2" lists exactly:
      | mandate | state |
      | M001    | ACCP  |

  Scenario: A resend re-reports all current mandate states ignoring the watermark
    Given mandate "M001" has projection state "ACCP"
    And mandate "M002" has projection state "PDNG"
    And the MRG window "w1" has already run
    When the MRG window "w2" runs as a resend
    Then the MRG report for window "w2" lists exactly:
      | mandate | state |
      | M001    | ACCP  |
      | M002    | PDNG  |

  Scenario: Replay by report id re-emits the same report deterministically
    Given mandate "M001" has projection state "ACCP"
    And the MRG window "w1" has already run
    When the window "w1" report is lost and replayed by its report id
    Then the replayed report is byte-identical to the original window "w1" report
