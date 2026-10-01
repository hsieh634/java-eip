package com.renewflow.eip.attendance;

/** 預期的業務衝突（對外是 409）。只有這個例外會變成 409；資料庫故障等一律不會被包裝成它。 */
public class AttendanceRuleException extends RuntimeException {

    public enum Rule {
        ALREADY_CLOCKED_IN,
        CLOCK_IN_REQUIRED,
        ALREADY_CLOCKED_OUT
    }

    private final Rule rule;

    public AttendanceRuleException(Rule rule, String message) {
        super(message);
        this.rule = rule;
    }

    public Rule getRule() {
        return rule;
    }
}
