package com.renewflow.eip.identity;

/** 只取帳號有效性需要的兩個欄位（不載入整個 Entity，也不取密碼雜湊）。 */
public record AccountStatus(boolean enabled, Role role) {
}
