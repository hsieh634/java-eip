package com.renewflow.eip.identity;

/**
 * 角色只回答「能做哪一類操作」；「能操作誰的資料」由各功能另外判斷。
 * 資料庫以字串保存（見 V1 的 CHECK），所以新增角色時要同時新增 migration。
 */
public enum Role {
    EMPLOYEE,
    MANAGER,
    ADMIN;

    /** Spring Security 的慣例：角色權限以 ROLE_ 開頭，hasRole("ADMIN") 會自動補上前綴。 */
    public String authority() {
        return "ROLE_" + name();
    }
}
